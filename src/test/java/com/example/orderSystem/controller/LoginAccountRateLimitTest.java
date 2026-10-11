package com.example.orderSystem.controller;

import com.example.orderSystem.util.PasswordUtils;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@TestPropertySource(properties = {
        "app.login-limit.ip.rate=100000",
        "app.login-limit.ip.interval=1m",
        "app.login-limit.user-ip.rate=5",
        "app.login-limit.user-ip.interval=15m",
        "app.login-limit.user.rate=20",
        "app.login-limit.user.interval=1h"
})
class LoginAccountRateLimitTest extends AbstractLoginRateLimitTest {

    @MockitoSpyBean
    private PasswordUtils passwordUtils;

    @Test
    @DisplayName("同一 IP 對 alice 失敗 5 次後,第 6 次以正確密碼仍回 401")
    void sixthAttemptOnSameAccountFromSameIpIsRejected(CapturedOutput output) throws Exception {
        String ip = "10.2.0.1";
        for (int i = 0; i < 5; i++) {
            login("alice", WRONG_PASSWORD, ip).andExpect(status().isUnauthorized());
        }

        login("alice", CORRECT_PASSWORD, ip).andExpect(status().isUnauthorized());

        assertThat(output).contains("layer=USER_IP account=alice ip=10.2.0.1");
    }

    @Test
    @DisplayName("攻擊者 IP 使 alice 達上限後,alice 從另一 IP 登入成功")
    void accountOwnerFromOtherIpIsNotAffected() throws Exception {
        for (int i = 0; i < 6; i++) {
            login("alice", WRONG_PASSWORD, "10.2.0.2");
        }

        login("alice", CORRECT_PASSWORD, "10.2.0.3").andExpect(status().isOk());
    }

    @Test
    @DisplayName("失敗 4 次 → 成功 → 再失敗 4 次 → 第 5 次仍進行密碼驗證")
    void successResetsAccountIpCounter() throws Exception {
        String ip = "10.2.0.4";
        for (int i = 0; i < 4; i++) {
            login("alice", WRONG_PASSWORD, ip).andExpect(status().isUnauthorized());
        }
        login("alice", CORRECT_PASSWORD, ip).andExpect(status().isOk());
        for (int i = 0; i < 4; i++) {
            login("alice", WRONG_PASSWORD, ip).andExpect(status().isUnauthorized());
        }

        login("alice", CORRECT_PASSWORD, ip).andExpect(status().isOk());
    }

    @Test
    @DisplayName("不存在的帳號同樣計數:第 6 次不進行密碼驗證")
    void nonExistentAccountIsCountedToo() throws Exception {
        String ip = "10.2.0.5";
        for (int i = 0; i < 6; i++) {
            login("nobody99", WRONG_PASSWORD, ip).andExpect(status().isUnauthorized());
        }

        verify(passwordUtils, times(5)).decryptPassword(anyString());
    }

    @Test
    @DisplayName("alice / Alice / ALICE 輪流累計,第 6 次被拒")
    void caseVariantsShareOneCounter() throws Exception {
        String ip = "10.2.0.6";
        String[] variants = {"alice", "Alice", "ALICE", "alice", "Alice"};
        for (String variant : variants) {
            login(variant, WRONG_PASSWORD, ip).andExpect(status().isUnauthorized());
        }

        login("aLiCe", CORRECT_PASSWORD, ip).andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("userId 超過 20 字元回 400,不留下任何限流計數")
    void tooLongUserIdIsRejectedWithoutCounting() throws Exception {
        login("a".repeat(21), WRONG_PASSWORD, "10.2.0.7").andExpect(status().isBadRequest());

        assertThat(loginLimitKeys()).isEmpty();
    }

    @Test
    @DisplayName("userId 含重音等非 ASCII 字元回 400,不留下任何限流計數")
    void nonAsciiUserIdIsRejectedWithoutCounting() throws Exception {
        login("alicé", WRONG_PASSWORD, "10.2.0.8").andExpect(status().isBadRequest());
        login("ålice", WRONG_PASSWORD, "10.2.0.8").andExpect(status().isBadRequest());

        assertThat(loginLimitKeys()).isEmpty();
    }

    private void exhaustAccountLimitFromUntrustedIps(String subnet) throws Exception {
        for (int i = 1; i <= 20; i++) {
            login("alice", WRONG_PASSWORD, subnet + i).andExpect(status().isUnauthorized());
        }
    }

    @Test
    @DisplayName("20 個不受信任 IP 各嘗試 alice 1 次後,第 21 個不受信任 IP 以正確密碼仍回 401")
    void distributedAttemptsOnOneAccountAreRejected(CapturedOutput output) throws Exception {
        exhaustAccountLimitFromUntrustedIps("10.3.1.");

        login("alice", CORRECT_PASSWORD, "10.3.1.100").andExpect(status().isUnauthorized());

        assertThat(output).contains("layer=USER account=alice ip=10.3.1.100");
    }

    @Test
    @DisplayName("alice 先從 IP T 登入成功;全域上限觸發後,從 IP T 以正確密碼登入成功")
    void trustedIpCanStillLogIn() throws Exception {
        String trusted = "10.3.2.200";
        login("alice", CORRECT_PASSWORD, trusted).andExpect(status().isOk());
        exhaustAccountLimitFromUntrustedIps("10.3.2.");

        login("alice", CORRECT_PASSWORD, trusted).andExpect(status().isOk());
    }

    @Test
    @DisplayName("全域上限觸發後,從未成功過的 IP 以正確密碼回 401,且不驗證密碼")
    void newIpIsRejectedWithoutPasswordCheck() throws Exception {
        exhaustAccountLimitFromUntrustedIps("10.3.3.");
        clearInvocations(passwordUtils);

        login("alice", CORRECT_PASSWORD, "10.3.3.100").andExpect(status().isUnauthorized());

        verify(passwordUtils, never()).decryptPassword(anyString());
    }

    @Test
    @DisplayName("受信任 IP 的失敗嘗試不消耗全域額度:只有成為受信任前的那 1 次被計入")
    void trustedIpFailuresDoNotConsumeAccountLimit() throws Exception {
        String trusted = "10.3.4.200";
        login("alice", CORRECT_PASSWORD, trusted).andExpect(status().isOk());
        for (int i = 0; i < 4; i++) {
            login("alice", WRONG_PASSWORD, trusted).andExpect(status().isUnauthorized());
        }
        clearInvocations(passwordUtils);

        for (int i = 1; i <= 19; i++) {
            login("alice", WRONG_PASSWORD, "10.3.4." + i).andExpect(status().isUnauthorized());
        }
        verify(passwordUtils, times(19)).decryptPassword(anyString());

        login("alice", WRONG_PASSWORD, "10.3.4.100").andExpect(status().isUnauthorized());
        verify(passwordUtils, times(19)).decryptPassword(anyString());
    }

    @Test
    @DisplayName("登入成功不歸零全域計數:受信任 IP 成功後,不受信任 IP 仍被拒")
    void successDoesNotResetAccountLimit() throws Exception {
        String trusted = "10.3.5.200";
        login("alice", CORRECT_PASSWORD, trusted).andExpect(status().isOk());
        exhaustAccountLimitFromUntrustedIps("10.3.5.");

        login("alice", CORRECT_PASSWORD, trusted).andExpect(status().isOk());

        login("alice", CORRECT_PASSWORD, "10.3.5.100").andExpect(status().isUnauthorized());
    }
}
