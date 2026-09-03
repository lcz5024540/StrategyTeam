ALTER TABLE cross_exchange_arb_order
  ADD COLUMN fee_amount DECIMAL(28,12) NOT NULL DEFAULT 0 AFTER average_price;
