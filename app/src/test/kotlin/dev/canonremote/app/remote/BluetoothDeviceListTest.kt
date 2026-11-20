package dev.canonremote.app.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BluetoothDeviceListTest {
    private fun d(address: String, name: String?) = BtDevice(address = address, name = name)

    private fun addresses(list: List<BtDevice>) = list.map { it.address }

    @Test
    fun bondedAreSortedByNameIgnoringCase() {
        val list = BluetoothDeviceList().withBonded(listOf(d("03", "zeta"), d("01", "Alpha"), d("02", "beta")))
        assertEquals(listOf("01", "02", "03"), addresses(list.paired))
    }

    @Test
    fun bondedWithoutNameKeptAndSortedByAddress() {
        val list = BluetoothDeviceList().withBonded(listOf(d("BB", "Pixel"), d("AA", null)))
        assertEquals(listOf("AA", "BB"), addresses(list.paired))
        assertEquals("AA", list.paired.first().label)
    }

    @Test
    fun bondedDedupedByAddress() {
        val list = BluetoothDeviceList().withBonded(listOf(d("01", "A"), d("01", "A bis"), d("02", "B")))
        assertEquals(listOf("01", "02"), addresses(list.paired))
    }

    @Test
    fun foundDevicesSortedAndDedupedByAddress() {
        val list = BluetoothDeviceList()
            .withFound(d("02", "Relais"))
            .withFound(d("01", "Galaxy"))
            .withFound(d("02", "Relais (Pixel)"))
        assertEquals(listOf("01", "02"), addresses(list.nearby))
        assertEquals("Relais (Pixel)", list.nearby[1].name)
    }

    @Test
    fun unnamedFoundDevicesIgnored() {
        val list = BluetoothDeviceList().withFound(d("01", null)).withFound(d("02", "  "))
        assertTrue(list.nearby.isEmpty())
    }

    @Test
    fun foundDeviceAlreadyBondedIsNotListedAsNearby() {
        val list = BluetoothDeviceList().withBonded(listOf(d("01", "Relais"))).withFound(d("01", "Relais"))
        assertTrue(list.nearby.isEmpty())
        assertEquals(listOf("01"), addresses(list.paired))
    }

    @Test
    fun newlyBondedDeviceMovesFromNearbyToPaired() {
        val list = BluetoothDeviceList()
            .withFound(d("01", "Relais"))
            .withFound(d("02", "Autre"))
            .withBonded(listOf(d("01", "Relais")))
        assertEquals(listOf("01"), addresses(list.paired))
        assertEquals(listOf("02"), addresses(list.nearby))
    }

    @Test
    fun allPutsBondedFirst() {
        val list = BluetoothDeviceList()
            .withFound(d("01", "Aaa"))
            .withBonded(listOf(d("02", "Zzz")))
        assertEquals(listOf("02", "01"), addresses(list.all))
    }

    @Test
    fun clearNearbyKeepsPaired() {
        val list = BluetoothDeviceList()
            .withBonded(listOf(d("01", "A")))
            .withFound(d("02", "B"))
            .clearNearby()
        assertEquals(listOf("01"), addresses(list.paired))
        assertTrue(list.nearby.isEmpty())
    }

    @Test
    fun pairStepConnectsWhenBondedWithoutCreatingABond() {
        var created = 0
        assertEquals(PairStep.CONNECT, pairStep(BondState.BONDED) { created++; true })
        assertEquals(0, created)
    }

    @Test
    fun pairStepWaitsForABondAlreadyInProgress() {
        var created = 0
        // Tapping a device again while Android pairs it: wait for that bond, no « impossible ».
        assertEquals(PairStep.WAIT_FOR_BOND, pairStep(BondState.BONDING) { created++; false })
        assertEquals(0, created)
    }

    @Test
    fun pairStepCreatesABondOtherwise() {
        assertEquals(PairStep.WAIT_FOR_BOND, pairStep(BondState.NONE) { true })
        assertEquals(PairStep.FAILED, pairStep(BondState.NONE) { false })
    }
}
