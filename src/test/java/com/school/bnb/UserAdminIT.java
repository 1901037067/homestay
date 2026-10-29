package com.school.bnb;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.school.bnb.common.BizException;
import com.school.bnb.common.ErrorCode;
import com.school.bnb.dto.UserReq;
import com.school.bnb.service.UserService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;

/** 账号管理（店长专属）：建号、改密、停用，以及"别把自己锁在门外"的两道守卫。 */
@AutoConfigureMockMvc
class UserAdminIT extends AbstractMysqlIT {

    private static final long P1 = 1L;

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper json;
    @Autowired UserService userService;
    @Autowired JdbcTemplate jdbc;

    private MockHttpSession login(String username) throws Exception {
        return login(username, "Admin@123");
    }

    private MockHttpSession login(String username, String password) throws Exception {
        return (MockHttpSession) mockMvc.perform(loginCall(username, password))
                .andExpect(status().isOk()).andReturn().getRequest().getSession(false);
    }

    /** 密码被重置之后，旧密码登不进去正是要断言的事 —— 那条路径拿不到 session，只能看返回码 */
    private int loginCode(String username, String password) throws Exception {
        return json.readTree(mockMvc.perform(loginCall(username, password))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString())
                .path("code").asInt();
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder loginCall(
            String username, String password) {
        return post("/api/login").contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"" + username + "\",\"password\":\"" + password + "\"}");
    }

    private int code(MockHttpSession session, String url) throws Exception {
        return json.readTree(mockMvc.perform(get(url).session(session))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString())
                .path("code").asInt();
    }

    private int saveUser(long id, UserReq req) throws Exception {
        return json.readTree(mockMvc.perform(put("/api/users/" + id).session(login("admin"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(req)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString())
                .path("code").asInt();
    }

    @Test
    @DisplayName("店长建的前台账号能登录，且只看得到绑定的那家民宿")
    void createdAccountWorksAndIsScoped() throws Exception {
        String username = "front" + System.nanoTime();
        var created = userService.save(new UserReq(null, username, "前台测试", "Admin@123", P1, true));

        MockHttpSession session = login(username);
        JsonNode properties = json.readTree(mockMvc.perform(get("/api/properties").session(session))
                .andReturn().getResponse().getContentAsString()).path("data");
        assertThat(properties).hasSize(1);
        assertThat(properties.get(0).path("id").asLong()).isEqualTo(P1);
        assertThat(created.manager()).as("绑了民宿就不是店长").isFalse();
        assertThat(created.propertyName()).isNotBlank();
    }

    @Test
    @DisplayName("店长账号（不绑民宿）看全部门店，也能进账号页")
    void managerAccountSeesEverything() throws Exception {
        String username = "boss" + System.nanoTime();
        var created = userService.save(new UserReq(null, username, "新店长", "Admin@123", null, true));
        assertThat(created.manager()).isTrue();

        MockHttpSession session = login(username);
        assertThat(json.readTree(mockMvc.perform(get("/api/properties").session(session))
                .andReturn().getResponse().getContentAsString()).path("data")).hasSizeGreaterThan(1);
        assertThat(code(session, "/api/users")).isZero();
    }

    @Test
    @DisplayName("用户名重复报 1004；密码太短报 400；列表里绝不带 password_hash")
    void validationAndNoHashLeak() throws Exception {
        String username = "dup" + System.nanoTime();
        userService.save(new UserReq(null, username, "重复测试", "Admin@123", P1, true));

        assertThatThrownBy(() -> userService.save(
                new UserReq(null, username, "又一个", "Admin@123", P1, true)))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getCode())
                .isEqualTo(ErrorCode.DUPLICATE_USERNAME);

        assertThatThrownBy(() -> userService.save(
                new UserReq(null, "shorty" + System.nanoTime(), "短密码", "123", P1, true)))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getCode())
                .isEqualTo(ErrorCode.BAD_REQUEST);

        String raw = mockMvc.perform(get("/api/users").session(login("admin")))
                .andReturn().getResponse().getContentAsString();
        assertThat(raw).as("哈希进了 JSON 就是离线爆破的原料")
                .doesNotContain("passwordHash").doesNotContain("$2a$");
    }

    @Test
    @DisplayName("编辑时不传密码 = 不动密码；传了才重置")
    void passwordIsOptionalOnUpdate() throws Exception {
        String username = "pw" + System.nanoTime();
        var created = userService.save(new UserReq(null, username, "改密测试", "Admin@123", P1, true));

        assertThat(code(login(username), "/api/properties")).as("原密码可用").isZero();
        assertThat(saveUser(created.id(), new UserReq(created.id(), username, "改密测试-改名",
                null, P1, true))).isZero();
        assertThat(code(login(username), "/api/properties"))
                .as("没传密码就不该把人家的密码改掉").isZero();

        assertThat(saveUser(created.id(), new UserReq(created.id(), username, "改密测试-改名",
                "NewPass@2026", P1, true))).isZero();
        assertThat(loginCode(username, "Admin@123"))
                .as("旧密码应当已经不能登录").isEqualTo(ErrorCode.LOGIN_FAILED);
        assertThat(code(login(username, "NewPass@2026"), "/api/properties"))
                .as("新密码可用").isZero();
    }

    @Test
    @DisplayName("不能停用当前登录的账号，也不能让系统里一个店长都不剩")
    void guardsAgainstLockingEveryoneOut() throws Exception {
        MockHttpSession admin = login("admin");
        long adminId = jdbc.queryForObject("select id from sys_user where username = 'admin'", Long.class);

        assertThat(saveUserOn(admin, adminId, new UserReq(adminId, "admin", "店长", null, null, false)))
                .as("停用自己 = 单机部署下把自己锁在门外")
                .isEqualTo(ErrorCode.BAD_REQUEST);

        // 用 jdbc 把其它店长都降成前台，制造"只剩 admin 一个店长"的场景。
        // 绕过服务层是故意的：这里要测的就是服务层在那种状态下会不会拦
        jdbc.update("update sys_user set property_id = 1 where property_id is null and username <> 'admin'");
        assertThat(saveUserOn(admin, adminId, new UserReq(adminId, "admin", "店长", null, P1, true)))
                .as("把最后一个店长改成前台，之后没人能再管账号")
                .isEqualTo(ErrorCode.BAD_REQUEST);
    }

    private int saveUserOn(MockHttpSession session, long id, UserReq req) throws Exception {
        return json.readTree(mockMvc.perform(put("/api/users/" + id).session(session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(req)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString())
                .path("code").asInt();
    }
}
