-- AlterTable
ALTER TABLE "order_lines" ADD COLUMN     "breakdown" JSONB,
ADD COLUMN     "pricing" JSONB;

-- AlterTable
ALTER TABLE "sale_lines" ADD COLUMN     "breakdown" JSONB;

-- CreateTable
CREATE TABLE "pack_types" (
    "id" SERIAL NOT NULL,
    "name" TEXT NOT NULL,
    "default_size" INTEGER,
    "sort" INTEGER NOT NULL DEFAULT 0,
    "active" BOOLEAN NOT NULL DEFAULT true,

    CONSTRAINT "pack_types_pkey" PRIMARY KEY ("id")
);

-- CreateTable
CREATE TABLE "item_packs" (
    "id" SERIAL NOT NULL,
    "item_id" INTEGER NOT NULL,
    "pack_type_id" INTEGER NOT NULL,
    "size" INTEGER NOT NULL,
    "price" INTEGER NOT NULL,

    CONSTRAINT "item_packs_pkey" PRIMARY KEY ("id")
);

-- CreateIndex
CREATE UNIQUE INDEX "pack_types_name_key" ON "pack_types"("name");

-- CreateIndex
CREATE UNIQUE INDEX "item_packs_item_id_pack_type_id_key" ON "item_packs"("item_id", "pack_type_id");

-- CreateIndex
CREATE UNIQUE INDEX "item_packs_item_id_size_key" ON "item_packs"("item_id", "size");

-- AddForeignKey
ALTER TABLE "item_packs" ADD CONSTRAINT "item_packs_item_id_fkey" FOREIGN KEY ("item_id") REFERENCES "items"("id") ON DELETE CASCADE ON UPDATE CASCADE;

-- AddForeignKey
ALTER TABLE "item_packs" ADD CONSTRAINT "item_packs_pack_type_id_fkey" FOREIGN KEY ("pack_type_id") REFERENCES "pack_types"("id") ON DELETE RESTRICT ON UPDATE CASCADE;
