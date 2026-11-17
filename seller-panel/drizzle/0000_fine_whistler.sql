CREATE TABLE `audit` (
	`id` text PRIMARY KEY NOT NULL,
	`license_id` text NOT NULL,
	`action` text NOT NULL,
	`at` integer NOT NULL
);
--> statement-breakpoint
CREATE TABLE `licenses` (
	`id` text PRIMARY KEY NOT NULL,
	`key_hash` text NOT NULL,
	`key_suffix` text NOT NULL,
	`hwid` text,
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
CREATE UNIQUE INDEX `licenses_key_hash` ON `licenses` (`key_hash`);--> statement-breakpoint
CREATE UNIQUE INDEX `licenses_hwid` ON `licenses` (`hwid`);