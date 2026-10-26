package ph.marbingo.field.data

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import ph.marbingo.core.print.ReceiptData
import ph.marbingo.core.print.ReceiptLineItem
import ph.marbingo.core.print.toEscPos
import ph.marbingo.field.data.db.CustomerDao
import java.io.IOException
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.UUID

@SuppressLint("MissingPermission") // The UI requests BLUETOOTH_CONNECT before calling; SecurityException is handled anyway.
class PrinterRepositoryImpl(private val context: Context, private val customers: CustomerDao) : PrinterRepository {
    private val prefs = context.applicationContext.getSharedPreferences("marbingo_prefs", Context.MODE_PRIVATE)

    private val adapter: BluetoothAdapter?
        get() = context.getSystemService(BluetoothManager::class.java)?.adapter

    override fun pairedDevices(): List<Pair<String, String>> = try {
        adapter?.bondedDevices.orEmpty().map { (it.name ?: it.address) to it.address }.sortedBy { it.first.lowercase() }
    } catch (_: SecurityException) {
        emptyList()
    }

    override var selectedAddress: String?
        get() = prefs.getString(KEY_PRINTER, null)
        set(v) { prefs.edit().putString(KEY_PRINTER, v).apply() }

    override suspend fun printOrder(order: Order, session: Session) {
        val address = customers.get(order.storeClientUuid)?.address
        val data = ReceiptData(
            shopName = session.shopName,
            ref = order.reference,
            storeName = order.storeName,
            storeAddress = address,
            agentName = order.agentName ?: session.name,
            orderedAt = display(order.orderedAt),
            lines = order.lines.map { ReceiptLineItem(it.name, it.qty, it.unit, it.unitPrice, it.lineTotal, it.breakdown.orEmpty()) },
            total = order.total,
            notes = order.notes,
        )
        send(data.toEscPos())
    }

    override suspend fun printTest(session: Session) {
        val data = ReceiptData(
            shopName = session.shopName, ref = "TEST", storeName = "Test print", agentName = session.name,
            orderedAt = display(System.currentTimeMillis()),
            lines = listOf(ReceiptLineItem("Sample item", 2, "pc", 12550, 25100)),
            total = 25100, notes = "If you can read this, the printer works.",
        )
        send(data.toEscPos())
    }

    private suspend fun send(bytes: ByteArray) = withContext(Dispatchers.IO) {
        val address = selectedAddress ?: throw UserFacingException("Choose a printer in Settings first.")
        val bt = adapter ?: throw UserFacingException("This device has no Bluetooth.")
        try {
            if (!bt.isEnabled) throw UserFacingException("Bluetooth is off. Turn it on and try again.")
            val device = bt.getRemoteDevice(address)
            val socket = device.createRfcommSocketToServiceRecord(SPP_UUID)
            try {
                bt.cancelDiscovery()
                socket.connect()
                socket.outputStream.apply { write(bytes); flush() }
                delay(500) // let the printer drain its buffer before the link drops
            } finally {
                runCatching { socket.close() }
            }
        } catch (e: SecurityException) {
            throw UserFacingException("Bluetooth permission is needed to print. Allow it in Settings.")
        } catch (e: IllegalArgumentException) {
            throw UserFacingException("That printer address isn't valid. Pick the printer again.")
        } catch (e: IOException) {
            throw UserFacingException("Printer not reachable. Is it on and paired?")
        }
    }

    private fun display(millis: Long): String =
        DateTimeFormatter.ofPattern("MMM d, yyyy h:mm a", Locale.ENGLISH)
            .format(Instant.ofEpochMilli(millis).atZone(ZoneId.of("Asia/Manila")))

    private companion object {
        val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
        const val KEY_PRINTER = "printer"
    }
}
