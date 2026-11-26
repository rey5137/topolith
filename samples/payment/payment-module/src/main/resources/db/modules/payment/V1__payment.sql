-- snippet:start payment-migration
create table payment_charge (
    id varchar(64) primary key,
    reference varchar(255) not null,
    amount decimal(19, 2) not null,
    status varchar(32) not null,
    tags varchar(1024) not null default ''
);
-- snippet:end payment-migration
