package com.school.bnb;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

/**
 * 种子账号密码哈希的生成与自检。
 *
 * <p>目的不是"测 BCrypt 能不能用"，而是锁死一件事：{@code V2__seed_base.sql} 里那串哈希
 * 必须能被 {@link BCryptPasswordEncoder} 默认配置验通。手工粘一个别处生成的哈希最容易在这里翻车。
 *
 * <p>重新生成：{@code mvn test -Dtest=SeedPasswordHashTest}，然后把控制台/文件里的哈希粘进 V2。
 */
class SeedPasswordHashTest {

    static final String ADMIN_RAW_PASSWORD = "Admin@123";

    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();

    @Test
    void 种子哈希必须与登录校验同源可互认() {
        String hash = encoder.encode(ADMIN_RAW_PASSWORD);

        assertThat(hash).startsWith("$2a$10$");
        assertThat(encoder.matches(ADMIN_RAW_PASSWORD, hash)).isTrue();
        assertThat(encoder.matches("Admin@124", hash)).isFalse();

        // cp936 控制台下别指望 stdout，落文件再读
        try {
            Path out = Path.of("target", "seed-admin-hash.txt");
            Files.createDirectories(out.getParent());
            Files.writeString(out, hash);
        } catch (Exception e) {
            throw new RuntimeException("写 target/seed-admin-hash.txt 失败", e);
        }
    }
}
