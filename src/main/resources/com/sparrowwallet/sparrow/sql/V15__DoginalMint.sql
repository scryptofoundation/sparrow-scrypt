alter table pendingMint add column content varbinary(2097152) after etching;
alter table pendingMint add column feeRate double after content;
alter table pendingMint add column destination varchar(255) after feeRate;
alter table pendingMint add column stepsBroadcast integer after destination;
