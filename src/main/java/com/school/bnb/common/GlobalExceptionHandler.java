package com.school.bnb.common;

import java.sql.SQLIntegrityConstraintViolationException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

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
     * 房间有历史订单时外键会直接拒删（实施计划 2.6）。
     * 服务层本应先查后返 409，这里兜底，避免冒成 500 给前端。
     */
    @ExceptionHandler(SQLIntegrityConstraintViolationException.class)
    public R<Void> handleConstraint(SQLIntegrityConstraintViolationException e) {
        log.warn("约束冲突: {}", e.getMessage());
        return R.fail(ErrorCode.ROOM_HAS_HISTORY, "存在关联历史数据，禁止删除；请改用下架(OFFLINE)");
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<R<Void>> handleOther(Exception e) {
        log.error("未处理异常", e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(R.fail(500, "服务内部错误: " + e.getClass().getSimpleName()));
    }
}
