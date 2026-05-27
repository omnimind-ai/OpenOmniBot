use std::sync::Arc;

use axum::extract::State;
use axum::extract::ws::{Message, WebSocket, WebSocketUpgrade};
use axum::response::IntoResponse;
use dashmap::DashMap;
use futures::{SinkExt, StreamExt};
use tokio::sync::mpsc;

use crate::api::envelope::{ErrorPayload, IncomingFrame, OutgoingFrame};
use crate::api::router::ChannelRouter;
use crate::state::AppState;

#[derive(Clone)]
pub struct WsSession {
    pub state: AppState,
    pub out: mpsc::Sender<OutgoingFrame>,
    pub subscriptions: Arc<DashMap<String, SubscriptionHandle>>,
}

pub struct SubscriptionHandle {
    pub cancel: tokio::sync::Notify,
    pub channel: String,
}

impl WsSession {
    pub async fn send_response_ok(&self, request_id: String, value: serde_json::Value) {
        let _ = self
            .out
            .send(OutgoingFrame::MethodResponse {
                request_id,
                ok: true,
                value: Some(value),
                error: None,
            })
            .await;
    }
    pub async fn send_response_err(&self, request_id: String, code: &str, message: String) {
        let _ = self
            .out
            .send(OutgoingFrame::MethodResponse {
                request_id,
                ok: false,
                value: None,
                error: Some(ErrorPayload {
                    code: code.into(),
                    message,
                }),
            })
            .await;
    }
    /// Backend-initiated method call to Dart.
    pub async fn invoke_method(&self, channel: &str, method: &str, args: serde_json::Value) {
        let _ = self
            .out
            .send(OutgoingFrame::MethodInvoke {
                channel: channel.into(),
                method: method.into(),
                arguments: args,
            })
            .await;
    }
    pub async fn event_data(&self, sub_id: &str, data: serde_json::Value) {
        let _ = self
            .out
            .send(OutgoingFrame::EventData {
                subscription_id: sub_id.into(),
                data,
            })
            .await;
    }
    pub async fn event_end(&self, sub_id: &str) {
        let _ = self
            .out
            .send(OutgoingFrame::EventEnd {
                subscription_id: sub_id.into(),
            })
            .await;
    }
    pub fn cancel_subscription(&self, sub_id: &str) {
        if let Some((_, handle)) = self.subscriptions.remove(sub_id) {
            handle.cancel.notify_waiters();
        }
    }
}

pub async fn ws_endpoint(State(state): State<AppState>, ws: WebSocketUpgrade) -> impl IntoResponse {
    ws.on_upgrade(|socket| handle_socket(socket, state))
}

async fn handle_socket(socket: WebSocket, state: AppState) {
    let (mut sender, mut receiver) = socket.split();
    let (out_tx, mut out_rx) = mpsc::channel::<OutgoingFrame>(512);
    let session = Arc::new(WsSession {
        state,
        out: out_tx,
        subscriptions: Arc::new(DashMap::new()),
    });

    let writer = tokio::spawn(async move {
        while let Some(frame) = out_rx.recv().await {
            let text = match serde_json::to_string(&frame) {
                Ok(t) => t,
                Err(e) => {
                    tracing::error!(error=%e, "serialize outgoing frame");
                    continue;
                }
            };
            if sender.send(Message::Text(text.into())).await.is_err() {
                break;
            }
        }
    });

    while let Some(Ok(msg)) = receiver.next().await {
        let text = match msg {
            Message::Text(t) => t.to_string(),
            Message::Binary(_) => continue,
            Message::Close(_) => break,
            _ => continue,
        };
        let frame: IncomingFrame = match serde_json::from_str(&text) {
            Ok(f) => f,
            Err(e) => {
                tracing::warn!(error=%e, "drop invalid incoming frame");
                continue;
            }
        };
        match frame {
            IncomingFrame::MethodCall {
                request_id,
                channel,
                method,
                arguments,
            } => {
                let session = session.clone();
                tokio::spawn(async move {
                    match ChannelRouter::route(&channel, &method, arguments, session.clone()).await
                    {
                        Ok(value) => session.send_response_ok(request_id, value).await,
                        Err(e) => {
                            session
                                .send_response_err(request_id, e.code(), e.to_string())
                                .await
                        }
                    }
                });
            }
            IncomingFrame::EventListen {
                subscription_id,
                channel,
                arguments,
            } => {
                let session = session.clone();
                tokio::spawn(async move {
                    ChannelRouter::subscribe(&channel, &subscription_id, arguments, session).await;
                });
            }
            IncomingFrame::EventCancel { subscription_id } => {
                session.cancel_subscription(&subscription_id);
            }
            IncomingFrame::Ping => {
                let _ = session.out.send(OutgoingFrame::Pong).await;
            }
        }
    }
    writer.abort();
}
