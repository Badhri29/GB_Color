-- ============================================================
-- GB_Colors - MySQL 8.0 Reference Database Schema
-- NOTE: You do NOT need to run this file manually!
-- The Java backend automatically creates the 'gb_colors' schema
-- (createDatabaseIfNotExist=true) and all tables via Hibernate
-- JPA (ddl-auto=update) on startup. Compatible with MySQL Workbench.
-- ============================================================

CREATE DATABASE IF NOT EXISTS `gb_colors`
  CHARACTER SET utf8mb4
  COLLATE utf8mb4_unicode_ci;

USE `gb_colors`;

-- 1. Registered Users Table
CREATE TABLE IF NOT EXISTS `users` (
  `id` INT AUTO_INCREMENT PRIMARY KEY,
  `user_id` VARCHAR(24) NOT NULL UNIQUE,
  `email` VARCHAR(191) NOT NULL UNIQUE,
  `username` VARCHAR(100) NOT NULL,
  `password_hash` VARCHAR(255) NOT NULL,
  `vip_level` INT NOT NULL DEFAULT 1,
  `demo_balance` DECIMAL(12, 2) NOT NULL DEFAULT 1000.00,
  `real_balance` DECIMAL(12, 2) NOT NULL DEFAULT 0.00,
  `active_mode` ENUM('DEMO', 'REAL') NOT NULL DEFAULT 'DEMO',
  `total_won` DECIMAL(12, 2) NOT NULL DEFAULT 0.00,
  `total_lost` DECIMAL(12, 2) NOT NULL DEFAULT 0.00,
  `total_recharged` DECIMAL(12, 2) NOT NULL DEFAULT 0.00,
  `total_withdrawn` DECIMAL(12, 2) NOT NULL DEFAULT 0.00,
  `last_recharge_at` DATETIME NULL,
  `last_recharge_amt` DECIMAL(12, 2) NOT NULL DEFAULT 0.00,
  `last_withdraw_at` DATETIME NULL,
  `last_withdraw_amt` DECIMAL(12, 2) NOT NULL DEFAULT 0.00,
  `block_type` VARCHAR(16) NULL DEFAULT 'NONE',
  `blocked_until` DATETIME NULL,
  `block_reason` VARCHAR(255) NULL,
  `blocked_at` DATETIME NULL,
  `created_at` TIMESTAMP DEFAULT CURRENT_TIMESTAMP
) ENGINE=InnoDB;

-- 2. Active Login Sessions Table
CREATE TABLE IF NOT EXISTS `sessions` (
  `token` VARCHAR(128) PRIMARY KEY,
  `email` VARCHAR(191) NOT NULL,
  `role` ENUM('USER', 'ADMIN') NOT NULL DEFAULT 'USER',
  `created_at` TIMESTAMP DEFAULT CURRENT_TIMESTAMP
) ENGINE=InnoDB;

-- 3. Completed Game Rounds History
CREATE TABLE IF NOT EXISTS `game_rounds` (
  `id` INT AUTO_INCREMENT PRIMARY KEY,
  `period` VARCHAR(32) NOT NULL UNIQUE,
  `winning_number` TINYINT NOT NULL,
  `colors` VARCHAR(64) NOT NULL,
  `size` ENUM('big', 'small') NOT NULL,
  `total_collected` DECIMAL(14, 2) NULL DEFAULT 0.00,
  `total_payout` DECIMAL(14, 2) NULL DEFAULT 0.00,
  `house_profit` DECIMAL(14, 2) NULL DEFAULT 0.00,
  `created_at` TIMESTAMP DEFAULT CURRENT_TIMESTAMP
) ENGINE=InnoDB;

-- 4. User Bets History
CREATE TABLE IF NOT EXISTS `bets` (
  `id` VARCHAR(64) PRIMARY KEY,
  `period` VARCHAR(32) NOT NULL,
  `email` VARCHAR(191) NOT NULL,
  `bet_type` ENUM('COLOR', 'NUMBER', 'SIZE') NOT NULL,
  `choice` VARCHAR(20) NOT NULL,
  `amount` DECIMAL(12, 2) NOT NULL,
  `mode` ENUM('DEMO', 'REAL') NOT NULL,
  `status` ENUM('ACTIVE', 'WON', 'LOST', 'CANCELLED') NOT NULL DEFAULT 'ACTIVE',
  `payout` DECIMAL(12, 2) NOT NULL DEFAULT 0.00,
  `net_profit` DECIMAL(12, 2) NOT NULL DEFAULT 0.00,
  `result_number` TINYINT NULL,
  `created_at` TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  INDEX `idx_bets_email` (`email`),
  INDEX `idx_bets_period` (`period`)
) ENGINE=InnoDB;

-- 5. Wallet Transactions (Deposits & Withdrawals)
CREATE TABLE IF NOT EXISTS `transactions` (
  `id` VARCHAR(64) PRIMARY KEY,
  `email` VARCHAR(191) NOT NULL,
  `tx_type` ENUM('DEPOSIT', 'WITHDRAWAL') NOT NULL,
  `method` VARCHAR(50) NULL,
  `account_or_utr` VARCHAR(191) NULL,
  `amount` DECIMAL(12, 2) NOT NULL,
  `status` VARCHAR(64) NOT NULL,
  `created_at` TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  INDEX `idx_tx_email` (`email`)
) ENGINE=InnoDB;

-- 6. Admin Withdrawal Requests Queue
CREATE TABLE IF NOT EXISTS `withdraw_requests` (
  `id` VARCHAR(64) PRIMARY KEY,
  `email` VARCHAR(191) NOT NULL,
  `user_id` VARCHAR(24) NOT NULL,
  `username` VARCHAR(100) NOT NULL,
  `amount` DECIMAL(12, 2) NOT NULL,
  `account_details` VARCHAR(255) NOT NULL,
  `status` ENUM('PENDING', 'PAID', 'REJECTED') NOT NULL DEFAULT 'PENDING',
  `requested_at` TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  `processed_at` DATETIME NULL,
  INDEX `idx_wth_status` (`status`)
) ENGINE=InnoDB;

-- 7. Platform Config & Financial Ledger (Single Row id=1)
CREATE TABLE IF NOT EXISTS `platform_settings` (
  `id` INT PRIMARY KEY DEFAULT 1,
  `admin_email` VARCHAR(191) NOT NULL,
  `admin_password_hash` VARCHAR(255) NOT NULL,
  `outcome_mode` ENUM('SMART_PROFIT', 'FAIR_RANDOM', 'MANUAL') NOT NULL DEFAULT 'SMART_PROFIT',
  `total_house_profit` DECIMAL(14, 2) NOT NULL DEFAULT 0.00,
  `total_bets_collected_real` DECIMAL(14, 2) NOT NULL DEFAULT 0.00,
  `total_payouts_paid_real` DECIMAL(14, 2) NOT NULL DEFAULT 0.00,
  `total_recharges` DECIMAL(14, 2) NOT NULL DEFAULT 0.00,
  `total_withdrawals_paid` DECIMAL(14, 2) NOT NULL DEFAULT 0.00,
  `round_counter` INT NOT NULL DEFAULT 26,
  `smtp_host` VARCHAR(100) NOT NULL DEFAULT 'smtp.gmail.com',
  `smtp_port` INT NOT NULL DEFAULT 587,
  `smtp_user` VARCHAR(191) NULL,
  `smtp_pass` VARCHAR(255) NULL,
  `smtp_from_name` VARCHAR(100) NOT NULL DEFAULT 'GB_Colors',
  `cycle_duration_seconds` INT NULL DEFAULT 30,
  `bet_close_seconds` INT NULL DEFAULT 5,
  `admin_min_profit_percent` INT NULL DEFAULT 15,
  `upi_id` VARCHAR(120) NULL DEFAULT 'gbcolors.pay@upi',
  `upi_payee_name` VARCHAR(120) NULL DEFAULT 'GB_Colors',
  `razorpay_key_id` VARCHAR(128) NULL,
  `razorpay_key_secret` VARCHAR(255) NULL,
  `min_deposit_amount` INT NULL DEFAULT 100,
  `max_deposit_amount` INT NULL DEFAULT 50000,
  `min_withdraw_amount` INT NULL DEFAULT 200,
  `max_withdraw_amount` INT NULL DEFAULT 50000
) ENGINE=InnoDB;

-- 8. Deposit Requests (UPI UTR Queue & Razorpay Orders)
CREATE TABLE IF NOT EXISTS `deposit_requests` (
  `id` VARCHAR(64) PRIMARY KEY,
  `email` VARCHAR(191) NOT NULL,
  `user_id` VARCHAR(24) NOT NULL,
  `username` VARCHAR(100) NOT NULL,
  `amount` DECIMAL(12, 2) NOT NULL,
  `method` VARCHAR(32) NOT NULL DEFAULT 'UPI',
  `utr_or_payment_id` VARCHAR(191) NULL,
  `order_id` VARCHAR(128) NULL,
  `status` ENUM('PENDING', 'APPROVED', 'REJECTED') NOT NULL DEFAULT 'PENDING',
  `admin_note` VARCHAR(255) NULL,
  `requested_at` TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  `processed_at` DATETIME NULL,
  INDEX `idx_dep_status` (`status`),
  INDEX `idx_dep_email` (`email`)
) ENGINE=InnoDB;

-- 9. Persistent User Notifications
CREATE TABLE IF NOT EXISTS `user_notifications` (
  `id` VARCHAR(64) PRIMARY KEY,
  `email` VARCHAR(191) NOT NULL,
  `type` VARCHAR(32) NOT NULL DEFAULT 'INFO',
  `title` VARCHAR(160) NOT NULL,
  `message` VARCHAR(500) NOT NULL,
  `amount` DECIMAL(12, 2) NULL,
  `reference_id` VARCHAR(64) NULL,
  `is_read` BOOLEAN NOT NULL DEFAULT FALSE,
  `created_at` TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  INDEX `idx_ntf_email_read` (`email`, `is_read`)
) ENGINE=InnoDB;
