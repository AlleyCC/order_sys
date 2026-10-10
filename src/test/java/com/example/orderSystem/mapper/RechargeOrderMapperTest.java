package com.example.orderSystem.mapper;

import com.baomidou.mybatisplus.test.autoconfigure.MybatisPlusTest;
import com.example.orderSystem.config.MyBatisPlusConfig;
import com.example.orderSystem.entity.RechargeOrder;
import com.example.orderSystem.entity.Transaction;
import com.example.orderSystem.enums.RechargeStatus;
import com.example.orderSystem.enums.TradeType;
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
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import static com.example.orderSystem.support.TestFixtures.insertRechargeOrder;
import static com.example.orderSystem.support.TestFixtures.insertUser;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@MybatisPlusTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(MyBatisPlusConfig.class)
@ActiveProfiles("test")
class RechargeOrderMapperTest {

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

    private static final LocalDateTime PAID_AT = LocalDateTime.now().truncatedTo(ChronoUnit.SECONDS);

    @Autowired RechargeOrderMapper rechargeOrderMapper;
    @Autowired UserMapper userMapper;
    @Autowired TransactionMapper transactionMapper;

    @Test
    @DisplayName("WAITING → SUCCESS 影響 1 列並寫入 paid_at;同一筆再做一次影響 0 列")
    void markPaidOnlyOnce() {
        String userId = insertUser(userMapper, "rc-once", 0);
        String id = insertRechargeOrder(rechargeOrderMapper, "rc-once-1", userId, 500, RechargeStatus.WAITING);

        int first = rechargeOrderMapper.markPaidIfIn(id, PAID_AT, List.of(RechargeStatus.WAITING));
        int second = rechargeOrderMapper.markPaidIfIn(id, PAID_AT, List.of(RechargeStatus.WAITING));

        assertThat(first).isEqualTo(1);
        assertThat(second).isZero();
        RechargeOrder after = rechargeOrderMapper.selectById(id);
        assertThat(after.getStatus()).isEqualTo(RechargeStatus.SUCCESS);
        assertThat(after.getPaidAt()).isEqualTo(PAID_AT);
    }

    @Test
    @DisplayName("CLOSED 的單:只允許 WAITING 時不動,允許 WAITING/CLOSED 時才入帳(只有人工對單會這樣傳)")
    void closedOrderNeedsExplicitPermission() {
        String userId = insertUser(userMapper, "rc-closed", 0);
        String id = insertRechargeOrder(rechargeOrderMapper, "rc-closed-1", userId, 500, RechargeStatus.CLOSED);

        int auto = rechargeOrderMapper.markPaidIfIn(id, PAID_AT, List.of(RechargeStatus.WAITING));
        assertThat(auto).isZero();
        assertThat(rechargeOrderMapper.selectById(id).getStatus()).isEqualTo(RechargeStatus.CLOSED);

        int manual = rechargeOrderMapper.markPaidIfIn(id, PAID_AT,
                List.of(RechargeStatus.WAITING, RechargeStatus.CLOSED));
        assertThat(manual).isEqualTo(1);
        assertThat(rechargeOrderMapper.selectById(id).getStatus()).isEqualTo(RechargeStatus.SUCCESS);
    }

    @Test
    @DisplayName("關單 WAITING → CLOSED;已入帳的單關不掉")
    void closeOnlyWhileWaiting() {
        String userId = insertUser(userMapper, "rc-close", 0);
        String waiting = insertRechargeOrder(rechargeOrderMapper, "rc-close-w", userId, 500, RechargeStatus.WAITING);
        String paid = insertRechargeOrder(rechargeOrderMapper, "rc-close-s", userId, 500, RechargeStatus.SUCCESS);

        assertThat(rechargeOrderMapper.closeIfWaiting(waiting)).isEqualTo(1);
        assertThat(rechargeOrderMapper.closeIfWaiting(paid)).isZero();
        assertThat(rechargeOrderMapper.selectById(waiting).getStatus()).isEqualTo(RechargeStatus.CLOSED);
        assertThat(rechargeOrderMapper.selectById(paid).getStatus()).isEqualTo(RechargeStatus.SUCCESS);
    }

    @Test
    @DisplayName("creditBalance:餘額增加指定金額")
    void creditBalanceAddsAmount() {
        String userId = insertUser(userMapper, "rc-credit", 300);

        int affected = userMapper.creditBalance(userId, 500);

        assertThat(affected).isEqualTo(1);
        assertThat(userMapper.selectById(userId).getBalance()).isEqualTo(800L);
    }

    @Test
    @DisplayName("同一筆儲值單寫第二筆流水 → 違反 UNIQUE(條件式入帳之外的第二道防線)")
    void secondTransactionForSameRechargeRejected() {
        String userId = insertUser(userMapper, "rc-uniq", 0);
        String id = insertRechargeOrder(rechargeOrderMapper, "rc-uniq-1", userId, 500, RechargeStatus.SUCCESS);

        transactionMapper.insert(rechargeTxn(userId, id));

        assertThatThrownBy(() -> transactionMapper.insert(rechargeTxn(userId, id)))
                .isInstanceOf(DuplicateKeyException.class);
    }

    private static Transaction rechargeTxn(String userId, String rechargeId) {
        Transaction txn = new Transaction();
        txn.setTransactionId(UUID.randomUUID().toString());
        txn.setUserId(userId);
        txn.setRechargeId(rechargeId);
        txn.setAmount(500);
        txn.setClosingBalance(500);
        txn.setType(TradeType.RECHARGE);
        txn.setCreatedBy(userId);
        return txn;
    }
}
