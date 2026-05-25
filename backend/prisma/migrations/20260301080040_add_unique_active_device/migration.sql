-- Enforce at most one active device per user using a partial unique index.
-- MySQL treats NULLs as non-duplicates, so the expression evaluates to NULL
-- for inactive devices and to 1 for active ones, ensuring at most one active
-- device per userEmail.
CREATE UNIQUE INDEX unique_active_device_per_user
  ON scheduler_device_state (
    userEmail,
    (CASE WHEN isActiveDevice = TRUE THEN 1 ELSE NULL END)
  );
