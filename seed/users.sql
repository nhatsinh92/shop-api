-- Demo users for the local demo ONLY. Passwords are documented in seed/README.md.
-- Requires the auth schema, which core creates with Flyway at startup. Hashes are BCrypt, cost 12.
-- Idempotent: existing emails are left untouched.
INSERT INTO auth.users (id, email, password_hash, display_name, member_tier) VALUES
  ('0b6f6d0e-6c1d-4f43-9d5e-1a0e5f1a0001', 'linh@example.com',
   '$2a$12$nAMUpvhBSn.Msq.t8hCedekGzzfbRcMOEwGI2MepOMA8mi8LtW6WS', 'Linh Nguyen', 'GOLD'),
  ('0b6f6d0e-6c1d-4f43-9d5e-1a0e5f1a0002', 'minh@example.com',
   '$2a$12$.xCHemhl6Y95.fnInearpu5NHxmpMq.3isnXNuqiWEPrnBmMsMQ7m', 'Minh Tran', 'SILVER'),
  ('0b6f6d0e-6c1d-4f43-9d5e-1a0e5f1a0003', 'an@example.com',
   '$2a$12$SOTwLNf11QbQRcFDeMQO7eNcoCF/f.UmM9LBDwMNpDnvvIPE7iaE2', 'An Le', 'STANDARD')
ON CONFLICT DO NOTHING;
