-- CreateEnum
CREATE TYPE "OrderStatus" AS ENUM ('pending', 'delivered', 'cancelled');

-- AlterTable
ALTER TABLE "items" ADD COLUMN     "wholesale_price" INTEGER;

-- AlterTable
ALTER TABLE "sales" ADD COLUMN     "customer_id" INTEGER;

-- CreateTable
CREATE TABLE "customers" (
    "id" SERIAL NOT NULL,
    "client_uuid" TEXT NOT NULL,
    "name" TEXT NOT NULL,
    "owner_name" TEXT,
    "phone" TEXT,
    "address" TEXT,
    "notes" TEXT,
    "active" BOOLEAN NOT NULL DEFAULT true,
    "created_by" INTEGER,
    "created_at" TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "updated_at" TIMESTAMPTZ NOT NULL,

    CONSTRAINT "customers_pkey" PRIMARY KEY ("id")
);

-- CreateTable
CREATE TABLE "orders" (
    "id" SERIAL NOT NULL,
    "client_uuid" TEXT NOT NULL,
    "customer_id" INTEGER NOT NULL,
    "agent_id" INTEGER NOT NULL,
    "status" "OrderStatus" NOT NULL DEFAULT 'pending',
    "ordered_at" TIMESTAMPTZ NOT NULL,
    "synced_at" TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "updated_at" TIMESTAMPTZ NOT NULL,
    "delivered_at" TIMESTAMPTZ,
    "delivered_by" INTEGER,
    "cancelled_reason" TEXT,
    "notes" TEXT,
    "total" INTEGER NOT NULL,
    "sale_id" INTEGER,

    CONSTRAINT "orders_pkey" PRIMARY KEY ("id")
);

-- CreateTable
CREATE TABLE "order_lines" (
    "id" SERIAL NOT NULL,
    "order_id" INTEGER NOT NULL,
    "item_id" INTEGER NOT NULL,
    "qty" INTEGER NOT NULL,
    "unit_price" INTEGER NOT NULL,
    "line_total" INTEGER NOT NULL,
    "delivered_qty" INTEGER,

    CONSTRAINT "order_lines_pkey" PRIMARY KEY ("id")
);

-- CreateIndex
CREATE UNIQUE INDEX "customers_client_uuid_key" ON "customers"("client_uuid");

-- CreateIndex
CREATE INDEX "customers_updated_at_idx" ON "customers"("updated_at");

-- CreateIndex
CREATE UNIQUE INDEX "orders_client_uuid_key" ON "orders"("client_uuid");

-- CreateIndex
CREATE UNIQUE INDEX "orders_sale_id_key" ON "orders"("sale_id");

-- CreateIndex
CREATE INDEX "orders_status_ordered_at_idx" ON "orders"("status", "ordered_at");

-- CreateIndex
CREATE INDEX "orders_agent_id_updated_at_idx" ON "orders"("agent_id", "updated_at");

-- AddForeignKey
ALTER TABLE "sales" ADD CONSTRAINT "sales_customer_id_fkey" FOREIGN KEY ("customer_id") REFERENCES "customers"("id") ON DELETE SET NULL ON UPDATE CASCADE;

-- AddForeignKey
ALTER TABLE "customers" ADD CONSTRAINT "customers_created_by_fkey" FOREIGN KEY ("created_by") REFERENCES "users"("id") ON DELETE SET NULL ON UPDATE CASCADE;

-- AddForeignKey
ALTER TABLE "orders" ADD CONSTRAINT "orders_customer_id_fkey" FOREIGN KEY ("customer_id") REFERENCES "customers"("id") ON DELETE RESTRICT ON UPDATE CASCADE;

-- AddForeignKey
ALTER TABLE "orders" ADD CONSTRAINT "orders_agent_id_fkey" FOREIGN KEY ("agent_id") REFERENCES "users"("id") ON DELETE RESTRICT ON UPDATE CASCADE;

-- AddForeignKey
ALTER TABLE "orders" ADD CONSTRAINT "orders_delivered_by_fkey" FOREIGN KEY ("delivered_by") REFERENCES "users"("id") ON DELETE SET NULL ON UPDATE CASCADE;

-- AddForeignKey
ALTER TABLE "orders" ADD CONSTRAINT "orders_sale_id_fkey" FOREIGN KEY ("sale_id") REFERENCES "sales"("id") ON DELETE SET NULL ON UPDATE CASCADE;

-- AddForeignKey
ALTER TABLE "order_lines" ADD CONSTRAINT "order_lines_order_id_fkey" FOREIGN KEY ("order_id") REFERENCES "orders"("id") ON DELETE CASCADE ON UPDATE CASCADE;

-- AddForeignKey
ALTER TABLE "order_lines" ADD CONSTRAINT "order_lines_item_id_fkey" FOREIGN KEY ("item_id") REFERENCES "items"("id") ON DELETE RESTRICT ON UPDATE CASCADE;
