pub mod entities;
pub mod repos;

use omnibot_common::{AppError, AppResult};
use sqlx::sqlite::{SqliteConnectOptions, SqlitePoolOptions};
use sqlx::{ConnectOptions, SqlitePool};
use std::path::Path;
use std::str::FromStr;

pub use entities::*;
pub use repos::*;

#[derive(Clone)]
pub struct DbHandle {
    pool: SqlitePool,
}

impl DbHandle {
    pub fn pool(&self) -> &SqlitePool { &self.pool }

    pub async fn open(db_path: &Path) -> AppResult<Self> {
        let url = format!("sqlite://{}", db_path.display());
        let opts = SqliteConnectOptions::from_str(&url)
            .map_err(|e| AppError::backend(format!("invalid sqlite url: {e}")))?
            .create_if_missing(true)
            .journal_mode(sqlx::sqlite::SqliteJournalMode::Wal)
            .synchronous(sqlx::sqlite::SqliteSynchronous::Normal)
            .busy_timeout(std::time::Duration::from_secs(5))
            .disable_statement_logging();

        let pool = SqlitePoolOptions::new()
            .max_connections(5)
            .connect_with(opts)
            .await
            .map_err(|e| AppError::backend(format!("open sqlite failed: {e}")))?;

        sqlx::migrate!("./migrations")
            .run(&pool)
            .await
            .map_err(|e| AppError::backend(format!("migration failed: {e}")))?;

        Ok(Self { pool })
    }
}
