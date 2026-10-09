package com.example.orderSystem.mapper;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.test.autoconfigure.MybatisPlusTest;
import com.example.orderSystem.config.MyBatisPlusConfig;
import com.example.orderSystem.entity.Notification;
import com.example.orderSystem.entity.User;
import com.example.orderSystem.enums.NotificationType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MySQLContainer;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@MybatisPlusTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(MyBatisPlusConfig.class)
@ActiveProfiles("test")
class NotificationMapperTest {

    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0")
            .withDatabaseName("paypool")
            .withUsername("root")
            .withPassword("test");

    static {
        MYSQL.start();
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
    }

    @Autowired NotificationMapper notificationMapper;
    @Autowired UserMapper userMapper;

    @Test
    @DisplayName("同一收件人、相同 dedup_key 寫第二次 → DuplicateKeyException")
    void duplicateDedupKeyRejected() {
        String user = seedUser("dup");
        notificationMapper.insert(notification(user, "ABANDONED:ord-1", null));

        assertThatThrownBy(() -> notificationMapper.insert(notification(user, "ABANDONED:ord-1", null)))
                .isInstanceOf(DuplicateKeyException.class);
    }

    @Test
    @DisplayName("dedup_key 為 NULL 的通知可以重複寫入(每次結算失敗都是新的一則)")
    void nullDedupKeyAllowsRepeats() {
        String user = seedUser("null");
        notificationMapper.insert(notification(user, null, null));
        notificationMapper.insert(notification(user, null, null));

        assertThat(unreadIds(user)).hasSize(2);
    }

    @Test
    @DisplayName("未讀查詢:只回該使用者、read_at 為 NULL 的通知,新的在前")
    void unreadOnlyOwnNewestFirst() {
        String me = seedUser("me");
        String other = seedUser("other");
        LocalDateTime base = LocalDateTime.of(2026, 10, 1, 12, 0);
        Notification older = notification(me, null, base);
        Notification newer = notification(me, null, base.plusMinutes(5));
        Notification read = notification(me, null, base.plusMinutes(10));
        read.setReadAt(base.plusMinutes(11));
        notificationMapper.insert(older);
        notificationMapper.insert(newer);
        notificationMapper.insert(read);
        notificationMapper.insert(notification(other, null, base));

        assertThat(unreadIds(me)).containsExactly(newer.getNotificationId(), older.getNotificationId());
    }

    @Test
    @DisplayName("標為已讀:自己的未讀通知影響 1 列,之後不再出現在未讀")
    void markOwnAsRead() {
        String me = seedUser("mark");
        Notification n = notification(me, null, null);
        notificationMapper.insert(n);

        assertThat(notificationMapper.markRead(n.getNotificationId(), me)).isEqualTo(1);
        assertThat(unreadIds(me)).isEmpty();
        assertThat(notificationMapper.markRead(n.getNotificationId(), me))
                .as("已讀過的再標一次不會更新")
                .isZero();
    }

    @Test
    @DisplayName("標為已讀:別人的通知影響 0 列,對方仍為未讀")
    void cannotMarkOthers() {
        String me = seedUser("me2");
        String other = seedUser("other2");
        Notification theirs = notification(other, null, null);
        notificationMapper.insert(theirs);

        assertThat(notificationMapper.markRead(theirs.getNotificationId(), me)).isZero();
        assertThat(unreadIds(other)).containsExactly(theirs.getNotificationId());
    }

    // ========== helpers ==========

    private List<String> unreadIds(String userId) {
        IPage<Notification> page = notificationMapper.selectUnread(new Page<>(1, 20), userId);
        return page.getRecords().stream().map(Notification::getNotificationId).toList();
    }

    private Notification notification(String userId, String dedupKey, LocalDateTime createdAt) {
        Notification n = new Notification();
        n.setNotificationId(UUID.randomUUID().toString());
        n.setUserId(userId);
        n.setOrderId("ord-1");
        n.setType(NotificationType.SETTLEMENT_INSUFFICIENT);
        n.setContent("測試通知");
        n.setDedupKey(dedupKey);
        n.setCreatedAt(createdAt);
        return n;
    }

    private String seedUser(String prefix) {
        User user = new User();
        user.setUserId(prefix + "-" + UUID.randomUUID().toString().substring(0, 8));
        user.setUserName("Notification Tester");
        user.setPassword("x");
        user.setBalance(0L);
        userMapper.insert(user);
        return user.getUserId();
    }
}
