// lib/models/chat_models.dart

import 'package:flutter/foundation.dart';
import 'package:ui/l10n/legacy_text_localizer.dart';
import 'block_models.dart';

// --- 简化的聊天消息数据模型 ---

/// 用户消息
class UserMessage {
  final String id;
  final String text;
  final DateTime timestamp;

  UserMessage({
    required this.text,
    String? id,
  }) : id = id ?? '${DateTime.now().millisecondsSinceEpoch}-user',
       timestamp = DateTime.now();
}

/// AI回复消息
class BotMessage {
  final String id;
  final String replyId;           // 对应AIResponse的reply_id
  final String? text;             // 可选的文本内容
  final List<Block> blocks;       // AI回复的blocks
  final DateTime timestamp;
  final bool isLoading;
  final String? actionText;
  final VoidCallback? onAction;
  final String? suggestionTitle;
  final List<Suggestion>? suggestions;

  BotMessage({
    required this.replyId,
    this.text,
    List<Block>? blocks,
    this.isLoading = false,
    this.actionText,
    this.onAction,
    this.suggestionTitle,
    this.suggestions,
    String? id,
  }) : id = id ?? '${DateTime.now().millisecondsSinceEpoch}-bot-$replyId',
       blocks = blocks ?? [],
       timestamp = DateTime.now();

  /// 从AIResponse创建BotMessage
  factory BotMessage.fromAIResponse({
    required AIResponse aiResponse,
    String? displayText,
    bool isLoading = false,
  }) {
    return BotMessage(
      replyId: aiResponse.replyId,
      text: displayText,
      blocks: aiResponse.blocks,
      isLoading: isLoading,
    );
  }
}

/// 统一的聊天消息接口（用于渲染）
class ChatMessage {
  final String id;
  final String text;
  final MessageType type;
  final DateTime timestamp;
  final List<Block>? blocks;
  final bool isLoading;
  final bool isFinalChunk; // 是否已完成该chunk回复
  final String? actionText;
  final VoidCallback? onAction;
  final String? suggestionTitle;
  final List<Suggestion>? suggestions;

  ChatMessage({
    required this.id,
    required this.text,
    required this.type,
    required this.timestamp,
    this.blocks,
    this.isLoading = false,
    this.isFinalChunk = false,
    this.actionText,
    this.onAction,
    this.suggestionTitle,
    this.suggestions,
  });

  /// 从用户消息创建
  factory ChatMessage.fromUserMessage(UserMessage userMessage) {
    return ChatMessage(
      id: userMessage.id,
      text: userMessage.text,
      type: MessageType.user,
      timestamp: userMessage.timestamp,
    );
  }

  /// 从AI消息创建
  factory ChatMessage.fromBotMessage(BotMessage botMessage) {
    return ChatMessage(
      id: botMessage.id,
      text: botMessage.text ?? LegacyTextLocalizer.localize('正在回复...'),
      type: MessageType.bot,
      timestamp: botMessage.timestamp,
      blocks: botMessage.blocks,
      isLoading: botMessage.isLoading,
      isFinalChunk: false,
      actionText: botMessage.actionText,
      onAction: botMessage.onAction,
      suggestionTitle: botMessage.suggestionTitle,
      suggestions: botMessage.suggestions,
    );
  }

  ChatMessage copyWith({
    String? id,
    String? text,
    MessageType? type,
    DateTime? timestamp,
    List<Block>? blocks,
    bool? isLoading,
    bool? isFinalChunk,
    String? actionText,
    VoidCallback? onAction,
    String? suggestionTitle,
    List<Suggestion>? suggestions,
  }) {
    return ChatMessage(
      id: id ?? this.id,
      text: text ?? this.text,
      type: type ?? this.type,
      timestamp: timestamp ?? this.timestamp,
      blocks: blocks ?? this.blocks,
      isLoading: isLoading ?? this.isLoading,
      isFinalChunk: isFinalChunk ?? this.isFinalChunk,
      actionText: actionText ?? this.actionText,
      onAction: onAction ?? this.onAction,
      suggestionTitle: suggestionTitle ?? this.suggestionTitle,
      suggestions: suggestions ?? this.suggestions,
    );
  }

  /// 兼容原有的Sender枚举
  Sender get sender => type == MessageType.user ? Sender.user : Sender.bot;
}

/// 消息类型
enum MessageType { user, bot }

/// 兼容原有的枚举
enum Sender { user, bot }

/// 建议模型
class Suggestion {
  final String text;
  Suggestion({required this.text});
}

// 兼容原有Message类的转换
class Message {
  final String id;
  final String text;
  final Sender sender;
  final bool isLoading;
  final DateTime timestamp;
  final String? actionText;
  final VoidCallback? onAction;
  final String? suggestionTitle;
  final List<Suggestion>? suggestions;
  final List<Block>? blocks;

  Message({
    required this.text,
    required this.sender,
    this.isLoading = false,
    this.actionText,
    this.onAction,
    this.suggestionTitle,
    this.suggestions,
    this.blocks,
    String? id,
  }) : id = id ?? '${DateTime.now().millisecondsSinceEpoch}-${text.hashCode}',
       timestamp = DateTime.now();

  // 兼容原有的工厂方法
  factory Message.fromAIResponse({
    required AIResponse aiResponse,
    String? displayText,
  }) {
    return Message(
      text: displayText ?? 'AI Response',
      sender: Sender.bot,
      blocks: aiResponse.blocks,
    );
  }

  // 从ChatMessage创建Message（兼容性转换）
  factory Message.fromChatMessage(ChatMessage chatMessage) {
    return Message(
      id: chatMessage.id,
      text: chatMessage.text,
      sender: chatMessage.sender,
      isLoading: chatMessage.isLoading,
      blocks: chatMessage.blocks,
      actionText: chatMessage.actionText,
      onAction: chatMessage.onAction,
      suggestionTitle: chatMessage.suggestionTitle,
      suggestions: chatMessage.suggestions,
    );
  }
}