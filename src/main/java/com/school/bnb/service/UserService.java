package com.school.bnb.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.school.bnb.common.AccessScope;
import com.school.bnb.common.AuthContext;
import com.school.bnb.common.BizException;
import com.school.bnb.common.ErrorCode;
import com.school.bnb.dto.UserReq;
import com.school.bnb.entity.SysUser;
import com.school.bnb.mapper.SysUserMapper;
import com.school.bnb.vo.UserVO;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 账号管理（店长专属）。
 *
 * <p><b>没有自助注册，也没有物理删除。</b>这是前台用的单机系统：账号是店长开给具体某个人的，
 * 不是自己注册进来的；而日志里的操作人只存了 user_id，删掉账号会让历史审计指向幽灵用户 ——
 * 所以"不要了"一律走停用，与房间域"下架替代删除"是同一条理由。
 */
@Service
public class UserService {

    private static final int MIN_PASSWORD = 8;

    private final SysUserMapper userMapper;
    private final PropertyService propertyService;
    private final OperationLogService opLog;
    private final AccessScope scope;
    private final org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder encoder =
            new org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder();

    public UserService(SysUserMapper userMapper, PropertyService propertyService,
                       OperationLogService opLog, AccessScope scope) {
        this.userMapper = userMapper;
        this.propertyService = propertyService;
        this.opLog = opLog;
        this.scope = scope;
    }

    public List<UserVO> list() {
        scope.requireManager();
        Map<Long, String> names = propertyService.nameMap();
        return userMapper.selectList(new LambdaQueryWrapper<SysUser>().orderByAsc(SysUser::getId))
                .stream()
                .map(u -> UserVO.of(u, names.get(u.getPropertyId())))
                .toList();
    }

    @Transactional
    public UserVO save(UserReq req) {
        scope.requireManager();
        String username = req.username().trim();
        boolean create = req.id() == null;
        SysUser existing = create ? null : require(req.id());

        if (req.propertyId() != null) {
            // 只能把账号绑到一家真实存在的民宿。归属调整本身不用再看可见范围 ——
            // 上面的 requireManager() 已经保证这里只有店长能进来
            propertyService.require(req.propertyId());
        }
        // 省略 enabled 不能回落成"启用"：更新时保持库里那个值，新建时才默认启用。
        // 否则调一次"改显示名"就会把停用的账号悄悄放回来 —— 与"部分更新回落成硬编码默认"同族
        boolean enabled = req.enabled() != null ? req.enabled()
                : create || Boolean.TRUE.equals(existing.getEnabled());

        if (create) {
            if (usernameTaken(username, null)) {
                throw new BizException(ErrorCode.DUPLICATE_USERNAME, "用户名已存在: " + username);
            }
            String raw = req.password();
            if (raw == null || raw.isBlank()) {
                throw new BizException(ErrorCode.BAD_REQUEST, "新建账号必须设置初始密码");
            }
            if (raw.length() < MIN_PASSWORD) {
                throw new BizException(ErrorCode.BAD_REQUEST,
                        "密码至少 " + MIN_PASSWORD + " 位，收到 " + raw.length() + " 位");
            }
            SysUser u = new SysUser();
            u.setUsername(username);
            u.setDisplayName(req.displayName().trim());
            u.setPasswordHash(encoder.encode(raw));
            u.setPropertyId(req.propertyId());
            u.setEnabled(enabled);
            userMapper.insert(u);
            // 审计里放 UserVO 而不是实体：实体带 password_hash，进了 before/after JSON
            // 就等于把哈希复制到另一张表，多一处要防的泄露面
            opLog.record("USER_CREATE", "sys_user", u.getId(), null,
                    "开通账号 " + u.getUsername() + "｜" + roleNote(u.getPropertyId(), u.getEnabled()),
                    null, UserVO.of(u, propertyName(u.getPropertyId())));
            return UserVO.of(u, propertyName(u.getPropertyId()));
        }

        if (!existing.getUsername().equals(username) && usernameTaken(username, existing.getId())) {
            throw new BizException(ErrorCode.DUPLICATE_USERNAME, "用户名已存在: " + username);
        }
        // 停用自己 / 把最后一个店长变成前台，都会把人锁在系统外面 —— 单机部署下这是最难恢复的一类事故
        if (!enabled && existing.getId().equals(AuthContext.currentUserId())) {
            throw new BizException(ErrorCode.BAD_REQUEST, "不能停用当前登录的账号");
        }
        boolean wasManager = existing.getPropertyId() == null && Boolean.TRUE.equals(existing.getEnabled());
        boolean staysManager = req.propertyId() == null && enabled;
        if (wasManager && !staysManager) {
            // 数店长与更新必须在同一把锁的两边。原来的"先 count 再 update"是 check-then-act：
            // 两个店长同时互相降级，两边都数到 2、都通过检查，结果一个店长都不剩（第十轮审阅第 4 条）
            List<Long> managers = userMapper.lockEnabledManagers();
            if (managers.size() <= 1) {
                throw new BizException(ErrorCode.BAD_REQUEST,
                        "系统里至少要留一个可用的店长账号：这个改动会让没有人能管账号");
            }
        }

        SysUser u = new SysUser();
        u.setId(existing.getId());
        u.setUsername(username);
        u.setDisplayName(req.displayName().trim());
        u.setPropertyId(req.propertyId());
        u.setEnabled(enabled);
        if (req.password() != null && !req.password().isBlank()) {
            if (req.password().length() < MIN_PASSWORD) {
                throw new BizException(ErrorCode.BAD_REQUEST,
                        "密码至少 " + MIN_PASSWORD + " 位，收到 " + req.password().length() + " 位");
            }
            u.setPasswordHash(encoder.encode(req.password()));
        }
        // 没传密码就是不动它：updateById 默认策略会跳过 null 字段，password_hash 保持原值
        userMapper.updateById(u);
        SysUser saved = require(existing.getId());
        opLog.record("USER_UPDATE", "sys_user", saved.getId(), null,
                "修改账号 " + saved.getUsername() + "｜" + roleNote(saved.getPropertyId(), saved.getEnabled())
                        + (u.getPasswordHash() == null ? "" : "· 含重置密码"),
                UserVO.of(existing, propertyName(existing.getPropertyId())),
                UserVO.of(saved, propertyName(saved.getPropertyId())));
        return UserVO.of(saved, propertyName(saved.getPropertyId()));
    }

    /** 角色不单独存列，一律由"归属哪间民宿"派生 —— 日志里也要按同一口径说清楚。 */
    private static String roleNote(Long propertyId, Boolean enabled) {
        String role = propertyId == null ? "店长（全部门店）" : propertyId + " 号民宿前台";
        return role + (Boolean.TRUE.equals(enabled) ? "" : "·已停用");
    }

    private SysUser require(Long id) {
        SysUser u = userMapper.selectById(id);
        if (u == null) {
            throw new BizException(ErrorCode.USER_NOT_FOUND, "账号不存在: " + id);
        }
        return u;
    }

    private boolean usernameTaken(String username, Long selfId) {
        List<SysUser> rows = userMapper.selectList(
                new LambdaQueryWrapper<SysUser>().eq(SysUser::getUsername, username));
        return rows.stream().anyMatch(r -> !r.getId().equals(selfId));
    }

    private String propertyName(Long propertyId) {
        return propertyId == null ? null : propertyService.nameMap().get(propertyId);
    }
}
