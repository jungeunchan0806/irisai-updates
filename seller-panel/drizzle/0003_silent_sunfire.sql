CREATE TABLE `device_nonces` (
	`id` text PRIMARY KEY NOT NULL,
	`expires_at` integer NOT NULL
);
--> statement-breakpoint
ALTER TABLE `seller_keys` ADD `group_id` text;--> statement-breakpoint
ALTER TABLE `seller_keys` ADD `platform` text;--> statement-breakpoint
ALTER TABLE `seller_keys` ADD `device_hash` text;--> statement-breakpoint
ALTER TABLE `seller_keys` ADD `key_cipher` text;