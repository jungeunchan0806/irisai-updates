import { sqliteTable, text, integer, uniqueIndex } from 'drizzle-orm/sqlite-core';
export const licenses = sqliteTable('licenses', {
 id: text('id').primaryKey(), keyHash: text('key_hash').notNull(), keySuffix: text('key_suffix').notNull(),
 hwid: text('hwid'), sellerId: text('seller_id'), note: text('note').notNull().default(''), durationMs: integer('duration_ms'), permanent: integer('permanent').notNull().default(0),
 activatedAt: integer('activated_at'), expiresAt: integer('expires_at'), revoked: integer('revoked').notNull().default(0),
 createdAt: integer('created_at').notNull(), updatedAt: integer('updated_at').notNull(),
}, t => [uniqueIndex('licenses_key_hash').on(t.keyHash), uniqueIndex('licenses_hwid').on(t.hwid)]);
export const audit = sqliteTable('audit', {
 id: text('id').primaryKey(), licenseId: text('license_id').notNull(), action: text('action').notNull(), at: integer('at').notNull()
});
export const sellerKeys = sqliteTable('seller_keys', {
 id:text('id').primaryKey(), keyHash:text('key_hash').notNull(), keySuffix:text('key_suffix').notNull(), note:text('note').notNull().default(''),
 durationMs:integer('duration_ms'), permanent:integer('permanent').notNull().default(0), activatedAt:integer('activated_at'), expiresAt:integer('expires_at'), revoked:integer('revoked').notNull().default(0),
 groupId:text('group_id'), platform:text('platform'), deviceHash:text('device_hash'), keyCipher:text('key_cipher'),
 createdAt:integer('created_at').notNull(), updatedAt:integer('updated_at').notNull(),
},t=>[uniqueIndex('seller_keys_key_hash').on(t.keyHash)]);
export const sellerSessions = sqliteTable('seller_sessions', {
 tokenHash:text('token_hash').primaryKey(), sellerId:text('seller_id').notNull().references(()=>sellerKeys.id), expiresAt:integer('expires_at').notNull(), createdAt:integer('created_at').notNull(),
});
export const adminAccount = sqliteTable('admin_account', {
 id:integer('id').primaryKey(), username:text('username').notNull(), passwordHash:text('password_hash').notNull(), updatedAt:integer('updated_at').notNull(),
});
export const adminSessions = sqliteTable('admin_sessions', {
 tokenHash:text('token_hash').primaryKey(), expiresAt:integer('expires_at').notNull(), createdAt:integer('created_at').notNull(),
});
export const authLimits = sqliteTable('auth_limits', {
 bucket:text('bucket').primaryKey(), attempts:integer('attempts').notNull(), expiresAt:integer('expires_at').notNull(),
});
export const deviceNonces = sqliteTable('device_nonces', {
 id:text('id').primaryKey(), expiresAt:integer('expires_at').notNull(),
});
