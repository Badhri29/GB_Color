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
  `smtp_from_name` VARCHAR(100) NOT NULL DEFAULT 'GB_Colors'
) ENGINE=InnoDB;
