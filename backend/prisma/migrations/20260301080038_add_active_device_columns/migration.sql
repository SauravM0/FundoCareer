-- AlterTable: Add active device tracking columns to scheduler_device_state
ALTER TABLE `scheduler_device_state`
  ADD COLUMN `deviceName` VARCHAR(191) NULL,
  ADD COLUMN `isActiveDevice` BOOLEAN NOT NULL DEFAULT false,
  ADD COLUMN `activeDeviceLastSeenAt` DATETIME(3) NULL,
  ADD COLUMN `activatedAt` DATETIME(3) NULL;

-- CreateIndex: Composite index for active device lookups per user
CREATE INDEX `scheduler_device_state_userEmail_isActiveDevice_idx` ON `scheduler_device_state`(`userEmail`, `isActiveDevice`);
