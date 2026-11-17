CREATE TABLE `seller_keys` (
	`id` text PRIMARY KEY NOT NULL,
	`key_hash` text NOT NULL,
	`key_suffix` text NOT NULL,
	`note` text DEFAULT '' NOT NULL,
	`duration_ms` integer,
	`permanent` integer DEFAULT 0 NOT NULL,
	`activated_at` integer,
	`expires_at` integer,
	`revoked` integer DEFAULT 0 NOT NULL,
	`created_at` integer NOT NULL,
	`updated_at` integer NOT NULL
);
--> statement-breakpoint
CREATE UNIQUE INDEX `seller_keys_key_hash` ON `seller_keys` (`key_hash`);--> statement-breakpoint
CREATE TABLE `seller_sessions` (
	`token_hash` text PRIMARY KEY NOT NULL,
	`seller_id` text NOT NULL,
	`expires_at` integer NOT NULL,
	`created_at` integer NOT NULL,
	FOREIGN KEY (`seller_id`) REFERENCES `seller_keys`(`id`) ON UPDATE no action ON DELETE no action
);
--> statement-breakpoint
ALTER TABLE `licenses` ADD `seller_id` text;