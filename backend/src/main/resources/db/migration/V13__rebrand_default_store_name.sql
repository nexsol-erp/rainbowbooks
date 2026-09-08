-- ---------------------------------------------------------------------------
-- The seeded store name is Rainbow Books, not Karvya
-- ---------------------------------------------------------------------------
--
-- Only touches the row if it still holds the seeded default - a shop that
-- already renamed itself through the admin settings screen keeps whatever
-- name it chose rather than having this migration silently overwrite it.

UPDATE site_setting SET setting_value = 'Rainbow Books'
 WHERE setting_key = 'store.name' AND setting_value = 'Karvya';
