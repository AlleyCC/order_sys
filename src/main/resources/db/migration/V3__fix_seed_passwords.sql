-- Fix seed data passwords: all users password = test1234 (BCrypt $2a$10$)
UPDATE `users` SET `password` = '$2a$10$6.M6feQoE1cqKTUwsePfpOnvpGikYlY6LNyq3i88nGTM3KU8ZBYZ2' WHERE `user_id` = 'admin';
UPDATE `users` SET `password` = '$2a$10$kXgdZvSmLZPZl0XO9w1c1OvIdKVzlpV/nDRpHizWOmMBZF0mRsEmS' WHERE `user_id` = 'alice';
UPDATE `users` SET `password` = '$2a$10$kXgdZvSmLZPZl0XO9w1c1OvIdKVzlpV/nDRpHizWOmMBZF0mRsEmS' WHERE `user_id` = 'bob';
UPDATE `users` SET `password` = '$2a$10$kXgdZvSmLZPZl0XO9w1c1OvIdKVzlpV/nDRpHizWOmMBZF0mRsEmS' WHERE `user_id` = 'charlie';
