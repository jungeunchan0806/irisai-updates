declare namespace Cloudflare {
  interface Env {
    DB?: D1Database;
    BUCKET?: R2Bucket;
    ADMIN_SETUP_HASH?: string;
    SELLER_KEY_ENCRYPTION_KEY?: string;
  }
}
