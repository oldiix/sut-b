package ua.course.rental;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.sql.Connection;
import java.sql.SQLException;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class RentalIntegrationTest {

    private Connection connection;
    private EquipmentRepository equipmentRepository;
    private RentalRepository rentalRepository;
    private RentalPolicy policy;
    private RentalService service;

    @BeforeEach
    void setUp() throws SQLException {
        connection = Database.connect();
        cleanDatabase();
        equipmentRepository = new EquipmentRepository(connection);
        rentalRepository = new RentalRepository(connection);
        policy = mock(RentalPolicy.class);
        when(policy.isAllowed(any(), anyInt(), anyInt())).thenReturn(true);
        service = new RentalService(equipmentRepository, rentalRepository, policy);
    }

    @AfterEach
    void tearDown() throws SQLException {
        connection.close();
    }

    private void cleanDatabase() throws SQLException {
        try (var statement = connection.createStatement()) {
            statement.executeUpdate("DELETE FROM rentals");
            statement.executeUpdate("DELETE FROM equipment");
        }
    }

    private Equipment createEquipment(String code, long dailyRate, int stock) throws SQLException {
        return equipmentRepository.create(new Equipment(0, code, dailyRate, stock));
    }

    private RentalOrder newActiveRental(String reference, Equipment item, int quantity, int days) {
        long total = item.dailyRate() * quantity * days;
        return new RentalOrder(0, reference, days, false, total, 0, total * 20 / 100, RentalStatus.Active,
                new RentalItem(0, item.id(), quantity, item.dailyRate()));
    }

    // ---------- Repository <-> Database ----------

    @Test
    @DisplayName("Обладнання зберігається в БД і повторно читається з тими самими даними")
    void equipment_saveAndFindById_repositoryState() throws SQLException {
        Equipment saved = createEquipment("DRILL-01", 2500, 4);

        Equipment found = equipmentRepository.findById(saved.id());

        assertTrue(saved.id() > 0);
        assertEquals(new Equipment(saved.id(), "DRILL-01", 2500, 4), found);
    }

    @Test
    @DisplayName("Оренда з позицією зберігається в БД і повторно читається разом із позицією")
    void rental_saveAndFindById_repositoryState() throws SQLException {
        Equipment item = createEquipment("SAW-01", 1000, 5);
        RentalOrder saved = rentalRepository.create(newActiveRental("R-REPO-1", item, 2, 3));

        RentalOrder found = rentalRepository.findById(saved.id());

        assertEquals(saved, found);
        assertEquals("R-REPO-1", found.reference());
        assertEquals(6000, found.total());
        assertEquals(1200, found.deposit());
        assertEquals(RentalStatus.Active, found.status());
        assertEquals(item.id(), found.item().equipmentId());
        assertEquals(2, found.item().quantity());
        assertEquals(1000, found.item().dailyRate());
    }

    @Test
    @DisplayName("Зміна обладнання зберігається в БД")
    void equipment_update_changesStoredValues() throws SQLException {
        Equipment item = createEquipment("LADDER-01", 800, 3);

        equipmentRepository.update(new Equipment(item.id(), "LADDER-01", 950, 7));

        Equipment found = equipmentRepository.findById(item.id());
        assertEquals(950, found.dailyRate());
        assertEquals(7, found.stock());
    }

    @Test
    @DisplayName("Видалення оренди каскадно видаляє позицію, після чого обладнання можна видалити")
    void rental_delete_removesRentalAndItem() throws SQLException {
        Equipment item = createEquipment("MIXER-01", 1500, 2);
        RentalOrder saved = rentalRepository.create(newActiveRental("R-DEL-1", item, 1, 1));

        rentalRepository.delete(saved.id());
        equipmentRepository.delete(item.id()); // спрацює лише якщо позицію оренди видалено

        assertNull(rentalRepository.findById(saved.id()));
        assertNull(equipmentRepository.findById(item.id()));
    }

    // ---------- Service -> Repository -> Database ----------

    @Test
    @DisplayName("Успішна оренда: запис Active з правильними сумою, заставою та знімком ціни")
    void rent_validRequest_createsActiveRental_serviceState() throws SQLException {
        Equipment item = createEquipment("CAMERA-01", 2000, 5);

        RentalOrder result = service.rent(new RentalRequest("R-OK-1", item.id(), 2, 3, false));

        RentalOrder stored = rentalRepository.findById(result.id());
        assertNotNull(stored);
        assertEquals(RentalStatus.Active, stored.status());
        assertEquals(12000, stored.total());
        assertEquals(2400, stored.deposit());
        assertEquals(0, stored.lateFee());
        assertEquals(2000, stored.item().dailyRate());
        assertEquals(1, rentalRepository.findRentals(item.id(), RentalStatus.Active).size());
        verify(policy, times(1)).isAllowed(item, 2, 3);
    }

    @Test
    @DisplayName("Зовнішня політика забороняє оренду: відмова, запис не створено")
    void rent_policyDenies_noRentalCreated_serviceState() throws SQLException {
        Equipment item = createEquipment("CAMERA-02", 2000, 5);
        when(policy.isAllowed(any(), anyInt(), anyInt())).thenReturn(false);

        assertThrows(RentalRejectedException.class,
                () -> service.rent(new RentalRequest("R-DENY-1", item.id(), 2, 3, false)));

        assertTrue(rentalRepository.findRentals(item.id(), RentalStatus.Active).isEmpty());
    }

    // Персональне правило: застава 20% суми, для преміального клієнта 10%, округлення донизу
    @ParameterizedTest(name = "преміальний={0} → застава {1} коп.")
    @DisplayName("Застава за персональним правилом зберігається окремо від суми оренди")
    @CsvSource({
            "false, 1398",
            "true,  699"
    })
    void rent_depositByPersonalRule_storedSeparatelyFromTotal(boolean premium, long expectedDeposit) throws SQLException {
        Equipment item = createEquipment("TENT-01", 333, 10);

        RentalOrder result = service.rent(new RentalRequest("R-DEP-1", item.id(), 3, 7, premium));

        RentalOrder stored = rentalRepository.findById(result.id());
        assertEquals(6993, stored.total());
        assertEquals(expectedDeposit, stored.deposit());
        assertEquals(premium, stored.premium());
    }

    @Test
    @DisplayName("Кількість дорівнює залишку: оренда дозволена, залишок у БД не змінюється")
    void rent_quantityEqualsStock_isAllowedAndStockUnchanged() throws SQLException {
        Equipment item = createEquipment("BIKE-01", 1200, 5);

        service.rent(new RentalRequest("R-EDGE-1", item.id(), 5, 1, false));

        assertEquals(1, rentalRepository.findRentals(item.id(), RentalStatus.Active).size());
        assertEquals(5, equipmentRepository.findById(item.id()).stock());
    }

    @Test
    @DisplayName("Кількість більша за залишок: відмова до звернення до зовнішньої політики")
    void rent_quantityAboveStock_isRejectedWithoutPolicyCall() throws SQLException {
        Equipment item = createEquipment("BIKE-02", 1200, 5);

        assertThrows(RentalRejectedException.class,
                () -> service.rent(new RentalRequest("R-STOCK-1", item.id(), 6, 1, false)));

        assertTrue(rentalRepository.findRentals(item.id(), RentalStatus.Active).isEmpty());
        verify(policy, never()).isAllowed(any(), anyInt(), anyInt());
    }

    @Test
    @DisplayName("Зовнішня політика недоступна: помилка передається далі, запис не створено")
    void rent_policyUnavailable_noRentalCreated() throws SQLException {
        Equipment item = createEquipment("DRONE-01", 5000, 2);
        when(policy.isAllowed(any(), anyInt(), anyInt())).thenThrow(new IllegalStateException("HTTP-перевірка недоступна"));

        assertThrows(IllegalStateException.class,
                () -> service.rent(new RentalRequest("R-HTTP-1", item.id(), 1, 2, false)));

        assertTrue(rentalRepository.findRentals(item.id(), RentalStatus.Active).isEmpty());
    }

    @Test
    @DisplayName("Повернення: статус Returned, сума і застава збережені, повторне повернення заборонене")
    void returnRental_changesStatus_secondReturnRejected() throws SQLException {
        Equipment item = createEquipment("PROJECTOR-01", 3000, 3);
        RentalOrder rented = service.rent(new RentalRequest("R-RET-1", item.id(), 1, 4, true));

        service.returnRental(rented.id());

        RentalOrder stored = rentalRepository.findById(rented.id());
        assertEquals(RentalStatus.Returned, stored.status());
        assertEquals(0, stored.lateFee());
        assertEquals(12000, stored.total());
        assertEquals(1200, stored.deposit());
        assertThrows(RentalRejectedException.class, () -> service.returnRental(rented.id()));
        verify(policy, times(1)).isAllowed(any(), anyInt(), anyInt());
    }

    @Test
    @DisplayName("Зміна ціни обладнання після оформлення не змінює збережену оренду")
    void rent_laterEquipmentChange_doesNotAffectStoredRental() throws SQLException {
        Equipment item = createEquipment("KAYAK-01", 4000, 2);
        RentalOrder rented = service.rent(new RentalRequest("R-SNAP-1", item.id(), 1, 2, false));

        equipmentRepository.update(new Equipment(item.id(), "KAYAK-01", 9000, 2));

        RentalOrder stored = rentalRepository.findById(rented.id());
        assertEquals(4000, stored.item().dailyRate());
        assertEquals(8000, stored.total());
    }
}