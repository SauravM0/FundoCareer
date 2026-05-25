-- AlterTable: Add scheduler tracking fields to scheduler_device_state
ALTER TABLE `scheduler_device_state`
  ADD COLUMN `intervalMinutes` INT NULL,
  ADD COLUMN `stoppedAt` DATETIME(3) NULL,
  ADD COLUMN `lastSetupEmailAt` DATETIME(3) NULL,
  ADD COLUMN `lastJobEmailAt` DATETIME(3) NULL;
