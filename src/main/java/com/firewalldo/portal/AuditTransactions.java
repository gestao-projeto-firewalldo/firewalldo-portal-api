package com.firewalldo.portal;

import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/** All controller writes share the transaction-local actor with database triggers. */
@Aspect
@Component
@Order(0)
class AuditTransactions {
    private final TransactionTemplate transaction;
    private final AuditService audit;
    AuditTransactions(PlatformTransactionManager manager, AuditService audit) {
        this.transaction = new TransactionTemplate(manager);
        this.audit = audit;
    }

    @Around("execution(* com.firewalldo.portal.*Controller.*(..))")
    Object write(ProceedingJoinPoint call) throws Throwable {
        var attributes = RequestContextHolder.getRequestAttributes();
        if (!(attributes instanceof ServletRequestAttributes servlet)) return call.proceed();
        var request = servlet.getRequest();
        if (request.getMethod().equals("GET") || request.getMethod().equals("HEAD")
            || request.getRequestURI().equals("/api/auth/login")) return call.proceed();
        return transaction.execute(tx -> {
            audit.context(request);
            try { return call.proceed(); }
            catch (RuntimeException | Error error) { throw error; }
            catch (Throwable error) { throw new IllegalStateException(error); }
        });
    }
}
