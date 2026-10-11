-- Late fees are no longer charged: drop the workspace's late fee setting.
DELETE FROM app_setting WHERE name = 'late_fee';
