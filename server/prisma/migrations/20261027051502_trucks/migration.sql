-- CreateEnum
CREATE TYPE "TruckMoveReason" AS ENUM ('load', 'topup', 'return', 'sale', 'count_variance');

-- CreateEnum
CREATE TYPE "PeriodStatus" AS ENUM ('draft', 'open', 'counting', 'closed');

-- CreateEnum
CREATE TYPE "TransferKind" AS ENUM ('load', 'topup', 'return');

-- CreateEnum
CREATE TYPE "TransferStatus" AS ENUM ('pending', 'approved', 'rejected');

-- AlterEnum
ALTER TYPE "Channel" ADD VALUE 'truck';

-- AlterEnum
-- This migration adds more than one value to an enum.
-- With PostgreSQL versions 11 and earlier, this is not possible
-- in a single migration. This can be worked around by creating
-- multiple migrations, each migration adding only one value to
-- the enum.


ALTER TYPE "MoveReason" ADD VALUE 'truck_load';
ALTER TYPE "MoveReason" ADD VALUE 'truck_return';

-- CreateTable
CREATE TABLE "trucks" (
    "id" SERIAL NOT NULL,
    "name" TEXT NOT NULL,
    "plate" TEXT,
    "driver_name" TEXT,
    "agent_id" INTEGER,
    "active" BOOLEAN NOT NULL DEFAULT true,
    "created_at" TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "updated_at" TIMESTAMPTZ NOT NULL,

    CONSTRAINT "trucks_pkey" PRIMARY KEY ("id")
);

-- CreateTable
CREATE TABLE "truck_stock" (
    "truck_id" INTEGER NOT NULL,
    "item_id" INTEGER NOT NULL,
    "qty" INTEGER NOT NULL,

    CONSTRAINT "truck_stock_pkey" PRIMARY KEY ("truck_id","item_id")
);

-- CreateTable
CREATE TABLE "truck_periods" (
    "id" SERIAL NOT NULL,
    "truck_id" INTEGER NOT NULL,
    "month" TEXT NOT NULL,
    "status" "PeriodStatus" NOT NULL DEFAULT 'draft',
    "opening_snapshot" JSONB,
    "opened_at" TIMESTAMPTZ,
    "opened_by" INTEGER,
    "count_started_at" TIMESTAMPTZ,
    "closed_at" TIMESTAMPTZ,
    "closed_by" INTEGER,
    "report" JSONB,
    "created_at" TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT "truck_periods_pkey" PRIMARY KEY ("id")
);

-- CreateTable
CREATE TABLE "truck_transfers" (
    "id" SERIAL NOT NULL,
    "truck_id" INTEGER NOT NULL,
    "period_id" INTEGER,
    "kind" "TransferKind" NOT NULL,
    "status" "TransferStatus" NOT NULL DEFAULT 'pending',
    "note" TEXT,
    "requested_by" INTEGER NOT NULL,
    "requested_at" TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "decided_by" INTEGER,
    "decided_at" TIMESTAMPTZ,

    CONSTRAINT "truck_transfers_pkey" PRIMARY KEY ("id")
);

-- CreateTable
CREATE TABLE "truck_transfer_lines" (
    "id" SERIAL NOT NULL,
    "transfer_id" INTEGER NOT NULL,
    "item_id" INTEGER NOT NULL,
    "qty" INTEGER NOT NULL,

    CONSTRAINT "truck_transfer_lines_pkey" PRIMARY KEY ("id")
);

-- CreateTable
CREATE TABLE "truck_moves" (
    "id" SERIAL NOT NULL,
    "truck_id" INTEGER NOT NULL,
    "item_id" INTEGER NOT NULL,
    "period_id" INTEGER NOT NULL,
    "change" INTEGER NOT NULL,
    "reason" "TruckMoveReason" NOT NULL,
    "at" TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "ref_type" TEXT,
    "ref_id" INTEGER,
    "by" INTEGER,

    CONSTRAINT "truck_moves_pkey" PRIMARY KEY ("id")
);

-- CreateTable
CREATE TABLE "truck_sales" (
    "id" SERIAL NOT NULL,
    "client_uuid" TEXT NOT NULL,
    "sale_id" INTEGER NOT NULL,
    "truck_id" INTEGER NOT NULL,
    "period_id" INTEGER NOT NULL,
    "oversold" BOOLEAN NOT NULL DEFAULT false,
    "late" BOOLEAN NOT NULL DEFAULT false,
    "synced_at" TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT "truck_sales_pkey" PRIMARY KEY ("id")
);

-- CreateTable
CREATE TABLE "truck_counts" (
    "id" SERIAL NOT NULL,
    "period_id" INTEGER NOT NULL,
    "counted_by" INTEGER NOT NULL,
    "counted_at" TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT "truck_counts_pkey" PRIMARY KEY ("id")
);

-- CreateTable
CREATE TABLE "truck_count_lines" (
    "id" SERIAL NOT NULL,
    "count_id" INTEGER NOT NULL,
    "item_id" INTEGER NOT NULL,
    "counted" INTEGER NOT NULL,

    CONSTRAINT "truck_count_lines_pkey" PRIMARY KEY ("id")
);

-- CreateIndex
CREATE UNIQUE INDEX "trucks_agent_id_key" ON "trucks"("agent_id");

-- CreateIndex
CREATE UNIQUE INDEX "truck_periods_truck_id_month_key" ON "truck_periods"("truck_id", "month");

-- CreateIndex
CREATE INDEX "truck_transfers_truck_id_status_idx" ON "truck_transfers"("truck_id", "status");

-- CreateIndex
CREATE INDEX "truck_moves_truck_id_item_id_idx" ON "truck_moves"("truck_id", "item_id");

-- CreateIndex
CREATE INDEX "truck_moves_period_id_idx" ON "truck_moves"("period_id");

-- CreateIndex
CREATE INDEX "truck_moves_item_id_at_idx" ON "truck_moves"("item_id", "at");

-- CreateIndex
CREATE UNIQUE INDEX "truck_sales_client_uuid_key" ON "truck_sales"("client_uuid");

-- CreateIndex
CREATE UNIQUE INDEX "truck_sales_sale_id_key" ON "truck_sales"("sale_id");

-- CreateIndex
CREATE INDEX "truck_sales_period_id_idx" ON "truck_sales"("period_id");

-- CreateIndex
CREATE UNIQUE INDEX "truck_counts_period_id_key" ON "truck_counts"("period_id");

-- CreateIndex
CREATE UNIQUE INDEX "truck_count_lines_count_id_item_id_key" ON "truck_count_lines"("count_id", "item_id");

-- AddForeignKey
ALTER TABLE "trucks" ADD CONSTRAINT "trucks_agent_id_fkey" FOREIGN KEY ("agent_id") REFERENCES "users"("id") ON DELETE SET NULL ON UPDATE CASCADE;

-- AddForeignKey
ALTER TABLE "truck_stock" ADD CONSTRAINT "truck_stock_truck_id_fkey" FOREIGN KEY ("truck_id") REFERENCES "trucks"("id") ON DELETE RESTRICT ON UPDATE CASCADE;

-- AddForeignKey
ALTER TABLE "truck_stock" ADD CONSTRAINT "truck_stock_item_id_fkey" FOREIGN KEY ("item_id") REFERENCES "items"("id") ON DELETE RESTRICT ON UPDATE CASCADE;

-- AddForeignKey
ALTER TABLE "truck_periods" ADD CONSTRAINT "truck_periods_truck_id_fkey" FOREIGN KEY ("truck_id") REFERENCES "trucks"("id") ON DELETE RESTRICT ON UPDATE CASCADE;

-- AddForeignKey
ALTER TABLE "truck_periods" ADD CONSTRAINT "truck_periods_opened_by_fkey" FOREIGN KEY ("opened_by") REFERENCES "users"("id") ON DELETE SET NULL ON UPDATE CASCADE;

-- AddForeignKey
ALTER TABLE "truck_periods" ADD CONSTRAINT "truck_periods_closed_by_fkey" FOREIGN KEY ("closed_by") REFERENCES "users"("id") ON DELETE SET NULL ON UPDATE CASCADE;

-- AddForeignKey
ALTER TABLE "truck_transfers" ADD CONSTRAINT "truck_transfers_truck_id_fkey" FOREIGN KEY ("truck_id") REFERENCES "trucks"("id") ON DELETE RESTRICT ON UPDATE CASCADE;

-- AddForeignKey
ALTER TABLE "truck_transfers" ADD CONSTRAINT "truck_transfers_period_id_fkey" FOREIGN KEY ("period_id") REFERENCES "truck_periods"("id") ON DELETE SET NULL ON UPDATE CASCADE;

-- AddForeignKey
ALTER TABLE "truck_transfers" ADD CONSTRAINT "truck_transfers_requested_by_fkey" FOREIGN KEY ("requested_by") REFERENCES "users"("id") ON DELETE RESTRICT ON UPDATE CASCADE;

-- AddForeignKey
ALTER TABLE "truck_transfers" ADD CONSTRAINT "truck_transfers_decided_by_fkey" FOREIGN KEY ("decided_by") REFERENCES "users"("id") ON DELETE SET NULL ON UPDATE CASCADE;

-- AddForeignKey
ALTER TABLE "truck_transfer_lines" ADD CONSTRAINT "truck_transfer_lines_transfer_id_fkey" FOREIGN KEY ("transfer_id") REFERENCES "truck_transfers"("id") ON DELETE CASCADE ON UPDATE CASCADE;

-- AddForeignKey
ALTER TABLE "truck_transfer_lines" ADD CONSTRAINT "truck_transfer_lines_item_id_fkey" FOREIGN KEY ("item_id") REFERENCES "items"("id") ON DELETE RESTRICT ON UPDATE CASCADE;

-- AddForeignKey
ALTER TABLE "truck_moves" ADD CONSTRAINT "truck_moves_truck_id_fkey" FOREIGN KEY ("truck_id") REFERENCES "trucks"("id") ON DELETE RESTRICT ON UPDATE CASCADE;

-- AddForeignKey
ALTER TABLE "truck_moves" ADD CONSTRAINT "truck_moves_item_id_fkey" FOREIGN KEY ("item_id") REFERENCES "items"("id") ON DELETE RESTRICT ON UPDATE CASCADE;

-- AddForeignKey
ALTER TABLE "truck_moves" ADD CONSTRAINT "truck_moves_period_id_fkey" FOREIGN KEY ("period_id") REFERENCES "truck_periods"("id") ON DELETE RESTRICT ON UPDATE CASCADE;

-- AddForeignKey
ALTER TABLE "truck_moves" ADD CONSTRAINT "truck_moves_by_fkey" FOREIGN KEY ("by") REFERENCES "users"("id") ON DELETE SET NULL ON UPDATE CASCADE;

-- AddForeignKey
ALTER TABLE "truck_sales" ADD CONSTRAINT "truck_sales_sale_id_fkey" FOREIGN KEY ("sale_id") REFERENCES "sales"("id") ON DELETE RESTRICT ON UPDATE CASCADE;

-- AddForeignKey
ALTER TABLE "truck_sales" ADD CONSTRAINT "truck_sales_truck_id_fkey" FOREIGN KEY ("truck_id") REFERENCES "trucks"("id") ON DELETE RESTRICT ON UPDATE CASCADE;

-- AddForeignKey
ALTER TABLE "truck_sales" ADD CONSTRAINT "truck_sales_period_id_fkey" FOREIGN KEY ("period_id") REFERENCES "truck_periods"("id") ON DELETE RESTRICT ON UPDATE CASCADE;

-- AddForeignKey
ALTER TABLE "truck_counts" ADD CONSTRAINT "truck_counts_period_id_fkey" FOREIGN KEY ("period_id") REFERENCES "truck_periods"("id") ON DELETE RESTRICT ON UPDATE CASCADE;

-- AddForeignKey
ALTER TABLE "truck_counts" ADD CONSTRAINT "truck_counts_counted_by_fkey" FOREIGN KEY ("counted_by") REFERENCES "users"("id") ON DELETE RESTRICT ON UPDATE CASCADE;

-- AddForeignKey
ALTER TABLE "truck_count_lines" ADD CONSTRAINT "truck_count_lines_count_id_fkey" FOREIGN KEY ("count_id") REFERENCES "truck_counts"("id") ON DELETE CASCADE ON UPDATE CASCADE;

-- AddForeignKey
ALTER TABLE "truck_count_lines" ADD CONSTRAINT "truck_count_lines_item_id_fkey" FOREIGN KEY ("item_id") REFERENCES "items"("id") ON DELETE RESTRICT ON UPDATE CASCADE;

-- One non-closed (draft/open/counting) period per truck.
CREATE UNIQUE INDEX "truck_periods_one_active" ON "truck_periods" ("truck_id") WHERE "status" <> 'closed';
