create table swapEntry (id identity not null, swapId varchar(32) not null, keyIndex integer not null, status varchar(32) not null, sealed varbinary(1000000) not null, updatedSeconds bigint not null, wallet bigint not null);
alter table swapEntry add constraint swapEntry_wallet_swapId_unique unique (wallet, swapId);
alter table swapEntry add constraint swapEntry_wallet_keyIndex_unique unique (wallet, keyIndex);
alter table swapEntry add constraint swapEntry_wallet foreign key (wallet) references wallet;
create table makerHistory (id identity not null, makerPubKey varchar(64) not null, completed integer not null, failed integer not null, noShow integer not null, wallet bigint not null);
alter table makerHistory add constraint makerHistory_wallet_makerPubKey_unique unique (wallet, makerPubKey);
alter table makerHistory add constraint makerHistory_wallet foreign key (wallet) references wallet;
