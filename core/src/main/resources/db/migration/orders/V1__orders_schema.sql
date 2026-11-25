-- Schema `orders` (`order` is an SQL keyword). Money is bigint VND.

create sequence order_number_seq;

create table orders (
    id uuid primary key,
    number text not null unique,
    user_id uuid not null,
    reservation_id uuid not null unique,
    status text not null,
    subtotal bigint not null check (subtotal >= 0),
    shipping_option text not null,
    shipping_fee bigint not null check (shipping_fee >= 0),
    total bigint not null check (total >= 0),
    payment_method text not null,
    payment_status text not null,
    shipping_address jsonb not null,
    note text,
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now()
);

create index orders_user_id_idx on orders (user_id, created_at desc);

create table order_items (
    order_id uuid not null references orders (id) on delete cascade,
    sku text not null,
    quantity integer not null check (quantity > 0),
    unit_price bigint not null check (unit_price >= 0),
    line_total bigint not null check (line_total >= 0),
    primary key (order_id, sku)
);

create table idempotency_keys (
    user_id uuid not null,
    key text not null,
    request_hash text not null,
    state text not null check (state in ('IN_PROGRESS', 'COMPLETED')),
    order_id uuid,
    response_status integer,
    response_body jsonb,
    created_at timestamptz not null default now(),
    primary key (user_id, key)
);

create index idempotency_keys_created_at_idx on idempotency_keys (created_at);
