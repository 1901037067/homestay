package com.school.bnb.common;

import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.resource.NoResourceFoundException;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(BizException.class)
    public ResponseEntity<R<Void>> handleBiz(BizException e) {
        HttpStatus status = e.getCode() == ErrorCode.UNAUTHORIZED
                ? HttpStatus.UNAUTHORIZED : HttpStatus.OK;
        return ResponseEntity.status(status).body(R.fail(e.getCode(), e.getMessage()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public R<Void> handleValid(MethodArgumentNotValidException e) {
        FieldError fe = e.getBindingResult().getFieldError();
        String msg = fe == null ? "参数不合法" : fe.getField() + " " + fe.getDefaultMessage();
        return R.fail(ErrorCode.BAD_REQUEST, msg);
    }

    /**
     * 数据库约束兜底。两类要分开报，否则前端拿到一样的提示没法处理：
     * 唯一键冲突（如同一民宿下房号重复）vs 外键拒删（房间有历史订单）。
     *
     * <p>接的是 Spring 的 DataIntegrityViolationException —— MyBatis-Spring 会翻译
     * JDBC 异常，直接 catch SQLIntegrityConstraintViolationException 永远进不来。
     */
    @ExceptionHandler(DataIntegrityViolationException.class)
    public R<Void> handleConstraint(DataIntegrityViolationException e) {
        String msg = rootMessage(e);
        log.warn("约束冲突: {}", msg);
        if (msg.contains("Duplicate entry")) {
            return R.fail(ErrorCode.DUPLICATE_KEY, "编号或名称已存在，请检查唯一字段");
        }
        if (msg.contains("foreign key")) {
            return R.fail(ErrorCode.HAS_CHILDREN, "存在关联数据，禁止删除；请改用下架(OFFLINE)");
        }
        return R.fail(ErrorCode.BAD_REQUEST, "数据不合法，请检查填写内容");
    }

    private static String rootMessage(Throwable e) {
        Throwable cur = e;
        while (cur.getCause() != null && cur.getCause() != cur) {
            cur = cur.getCause();
        }
        return cur.getMessage() == null ? e.getClass().getSimpleName() : cur.getMessage();
    }

    /** 方法不支持要报 405，否则会掉进 Exception 兜底变成 500。 */
    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<R<Void>> handleMethod(HttpRequestMethodNotSupportedException e) {
        return ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED)
                .body(R.fail(405, e.getMethod() + " 不被支持，可用：" + String.join("/",
                        e.getSupportedMethods() == null ? new String[0] : e.getSupportedMethods())));
    }

    /**
     * 接口路径不存在时，Spring 6 抛 NoResourceFoundException。不单独接的话它会掉进下面的
     * Exception 兜底、返回 500「服务内部错误」，前端排查时会被误导到业务代码里去。
     */
    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<R<Void>> handleNotFound(NoResourceFoundException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(R.fail(404, "接口不存在: /" + e.getResourcePath()));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<R<Void>> handleOther(Exception e) {
        log.error("未处理异常", e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(R.fail(500, "服务内部错误: " + e.getClass().getSimpleName()));
    }
}
