ALTER TABLE users
    MODIFY COLUMN role ENUM('GUEST', 'USER', 'ADMIN') NOT NULL DEFAULT 'USER';

-- Preserve legacy data while invalidating every token issued for the old shared guest identity.
UPDATE users
SET username = CONCAT('legacy_guest_disabled_', id),
    role = 'GUEST'
WHERE HEX(username) = 'E6B8B8E5AEA2';
