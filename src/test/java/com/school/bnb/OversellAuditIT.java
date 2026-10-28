package com.school.bnb;

import static org.assertj.core.api.Assertions.assertThat;

import com.school.bnb.dto.CreateBookingReq;
import com.school.bnb.dto.PropertyReq;
import com.school.bnb.dto.RoomReq;
import com.school.bnb.dto.RoomTypeReq;
import com.school.bnb.entity.Booking;
import com.school.bnb.entity.Room;
import com.school.bnb.service.BookingService;
import com.school.bnb.service.PropertyService;
import com.school.bnb.service.RoomService;
import com.school.bnb.service.RoomTypeService;
import com.school.bnb.vo.BookingVO;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 防超卖的真实入口审计。这组测试的存在理由：
 *
 * <p>W1 的 {@code BookingKernelIT} 直接调内核，{@code FOR UPDATE} 恰好是事务里第一条语句，
 * 快照在拿锁之后才建立 —— 所以它一直是绿的。而真实入口 {@code BookingService.create}
 * 在进内核之前要先查房间/房型/民宿，快照建立点被提前到拿锁之前，锁后那条普通 SELECT
 * 看不到别人刚提交的占用。实测 12 轮并发超卖 12 轮。
 *
 * <p>所以这里三件事分开证：
 * ① 机制在 RR 下确实存在（普通读）；
 * ② <b>当前读是解药</b> —— 同样的交错，把冲突查询换成 FOR UPDATE 就能看到，
 *    这一条与隔离级别配置无关，是修复本身在起作用；
 * ③ 真实入口在并发下不再超卖（回归防线）。
 */
class OversellAuditIT extends AbstractMysqlIT {

    private static final LocalDateTime DAY = LocalDateTime.of(2027, 3, 1, 0, 0);
    private static final LocalDateTime S = DAY.withHour(14);
    private static final LocalDateTime E = DAY.withHour(18);

    @Autowired DataSource ds;
    @Autowired JdbcTemplate jdbc;
    @Autowired BookingService bookingService;
    @Autowired PropertyService propertyService;
    @Autowired RoomTypeService roomTypeService;
    @Autowired RoomService roomService;

    private Room plainRoom() {
        String tag = String.valueOf(System.nanoTime());
        propertyService.save(new PropertyReq(null, "店" + tag, null, LocalTime.of(6, 0),
                LocalTime.of(8, 0), LocalTime.of(22, 0)));
        Long pid = propertyService.list().stream().filter(p -> p.name().equals("店" + tag))
                .findFirst().orElseThrow().id();
        roomTypeService.save(new RoomTypeReq(null, pid, "型" + tag, new BigDecimal("100.00"),
                2, new BigDecimal("20.00"), null, 0, 0, LocalTime.of(14, 0), LocalTime.of(12, 0)));
        Long tid = roomTypeService.list(pid).get(0).id();
        return roomService.save(new RoomReq(null, pid, tid, "R" + tag, 1, Room.ON_SALE));
    }

    private long seedBooking(Long roomId, String no) {
        jdbc.update("""
                insert into booking (booking_no, room_id, guest_name, start_time, end_time, status, amount)
                values (?, ?, '审计客人', ?, ?, 'CONFIRMED', 0)
                """, no, roomId, S, E);
        return jdbc.queryForObject("select id from booking where booking_no = ?", Long.class, no);
    }

    private int occupancyRows(Long roomId) {
        Integer n = jdbc.queryForObject("select count(1) from occupancy where room_id = ?", Integer.class, roomId);
        return n == null ? 0 : n;
    }

    /**
     * 手工排一遍危险交错：c1 普通读建快照 → c2 完整下单并提交 → c1 拿房间锁 → c1 查冲突。
     *
     * @param conflictQuery 冲突查询的 SQL，普通 SELECT 或 FOR UPDATE
     * @return c1 在拿锁之后看到的冲突行数
     */
    private int interleaving(Long roomId, long otherBookingId, long selfBookingId, String conflictQuery)
            throws Exception {
        try (Connection c1 = ds.getConnection(); Connection c2 = ds.getConnection()) {
            c1.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
            c2.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
            c1.setAutoCommit(false);
            c2.setAutoCommit(false);

            // c1 先做一次普通查询 —— 等价于 BookingService.ctx() 里那三条 select
            try (PreparedStatement ps = c1.prepareStatement("select id from room where id = ?")) {
                ps.setLong(1, roomId);
                ps.executeQuery();
            }

            // c2 走完整下单：锁房间 → 插占用 → 提交
            try (PreparedStatement lock = c2.prepareStatement("select id from room where id = ? for update")) {
                lock.setLong(1, roomId);
                lock.executeQuery();
            }
            insertOccupy(c2, roomId, otherBookingId);
            c2.commit();

            // c1 现在才拿房间锁（c2 已提交，不会阻塞），然后跑冲突查询
            try (PreparedStatement lock = c1.prepareStatement("select id from room where id = ? for update")) {
                lock.setLong(1, roomId);
                lock.executeQuery();
            }
            int seen;
            try (PreparedStatement cnt = c1.prepareStatement(conflictQuery)) {
                cnt.setLong(1, roomId);
                cnt.setObject(2, E);
                cnt.setObject(3, S);
                try (ResultSet rs = cnt.executeQuery()) {
                    rs.next();
                    seen = rs.getInt(1);
                }
            }
            // c1 按它"看到"的结果决定要不要插 —— 看到 0 就会插，形成超卖
            if (seen == 0) {
                insertOccupy(c1, roomId, selfBookingId);
            }
            c1.commit();
            return seen;
        }
    }

    private void insertOccupy(Connection c, Long roomId, long bookingId) throws Exception {
        try (PreparedStatement ins = c.prepareStatement(
                "insert into occupancy (room_id, booking_id, start_time, end_time, block_start, block_end, kind) "
              + "values (?,?,?,?,?,?, 'ROOM')", Statement.RETURN_GENERATED_KEYS)) {
            ins.setLong(1, roomId);
            ins.setLong(2, bookingId);
            ins.setObject(3, S);
            ins.setObject(4, E);
            ins.setObject(5, S);
            ins.setObject(6, E);
            ins.executeUpdate();
        }
    }

    @Test
    @DisplayName("机制核查：RR 下拿锁之后的普通 SELECT 仍看不到别人刚提交的占用，结果超卖")
    void plainReadGoesStaleEvenAfterLock() throws Exception {
        Room room = plainRoom();
        long b1 = seedBooking(room.getId(), "AUDIT-PLAIN-1");
        long b2 = seedBooking(room.getId(), "AUDIT-PLAIN-2");

        int seen = interleaving(room.getId(), b2, b1,
                "select count(1) from occupancy where room_id = ? and block_start < ? and block_end > ?");

        assertThat(seen).as("普通 SELECT 在拿锁后仍读旧快照").isZero();
        assertThat(occupancyRows(room.getId())).as("于是两单都插进去了 —— 超卖").isEqualTo(2);
    }

    @Test
    @DisplayName("解药核查：同样的交错，把冲突查询换成当前读就能看到，且不再超卖")
    void currentReadIsTheFix() throws Exception {
        Room room = plainRoom();
        long b1 = seedBooking(room.getId(), "AUDIT-CUR-1");
        long b2 = seedBooking(room.getId(), "AUDIT-CUR-2");

        // 与上一个测试唯一的差别：冲突查询带 FOR UPDATE（取 id，聚合不能加锁）
        int seen = interleaving(room.getId(), b2, b1,
                "select count(1) from (select id from occupancy "
              + "where room_id = ? and block_start < ? and block_end > ? for update) t");

        assertThat(seen).as("当前读必须看到 c2 已提交的占用").isEqualTo(1);
        assertThat(occupancyRows(room.getId())).as("看到冲突就不再插 —— 只有 c2 那一行").isEqualTo(1);
    }

    @Test
    @DisplayName("真实入口回归：走 BookingService.create 并发 12 轮，一次都不许超卖")
    void realEntryNeverOversells() throws Exception {
        int rounds = 12;
        List<String> log = new ArrayList<>();

        for (int i = 0; i < rounds; i++) {
            Room r = plainRoom();
            ExecutorService pool = Executors.newFixedThreadPool(2);
            CountDownLatch gate = new CountDownLatch(1);
            var task = (java.util.concurrent.Callable<String>) () -> {
                gate.await();
                try {
                    BookingVO b = bookingService.create(new CreateBookingReq(
                            r.getId(), "并发客", null, S, E, Booking.CONFIRMED, null));
                    return "OK:" + b.bookingNo();
                } catch (Exception ex) {
                    return "REJECT:" + ex.getClass().getSimpleName();
                }
            };
            var f1 = pool.submit(task);
            var f2 = pool.submit(task);
            gate.countDown();
            String s1 = f1.get(60, TimeUnit.SECONDS);
            String s2 = f2.get(60, TimeUnit.SECONDS);
            pool.shutdownNow();

            long ok = (s1.startsWith("OK") ? 1 : 0) + (s2.startsWith("OK") ? 1 : 0);
            int rows = occupancyRows(r.getId());
            log.add("round " + i + " ok=" + ok + " rows=" + rows + " [" + s1 + " | " + s2 + "]");
            assertThat(rows).as("第 %d 轮：同一时段占用行数", i).isEqualTo(1);
            assertThat(ok).as("第 %d 轮：成功单数", i).isEqualTo(1);
        }

        try {
            java.nio.file.Files.writeString(java.nio.file.Path.of("target", "oversell-audit.txt"),
                    String.join(System.lineSeparator(), log) + System.lineSeparator());
        } catch (Exception ignored) {
            // 诊断输出失败不该让测试失败
        }
    }

    @Test
    @DisplayName("幂等回放：同一个 requestId 重复提交返回同一单，只有一行占用")
    void requestIdIsIdempotent() {
        Room room = plainRoom();
        String rid = "REQ-" + System.nanoTime();

        BookingVO first = bookingService.create(new CreateBookingReq(
                room.getId(), "双击客", null, S, E, Booking.CONFIRMED, null, null, rid));
        BookingVO again = bookingService.create(new CreateBookingReq(
                room.getId(), "双击客", null, S, E, Booking.CONFIRMED, null, null, rid));

        assertThat(again.id()).isEqualTo(first.id());
        assertThat(again.bookingNo()).isEqualTo(first.bookingNo());
        assertThat(occupancyRows(room.getId())).isEqualTo(1);
    }
}
