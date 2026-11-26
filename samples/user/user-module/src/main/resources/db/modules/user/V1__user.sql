create table user_order (
    id varchar(64) primary key,
    payment_id varchar(64) not null,
    reference varchar(255) not null,
    amount decimal(19, 2) not null
);
