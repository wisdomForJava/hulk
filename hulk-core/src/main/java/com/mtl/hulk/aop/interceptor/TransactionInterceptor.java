package com.mtl.hulk.aop.interceptor;

import com.alibaba.fastjson.JSONObject;
import com.mtl.hulk.*;
import com.mtl.hulk.annotation.MTLDTActivity;
import com.mtl.hulk.annotation.MTLTwoPhaseAction;
import com.mtl.hulk.aop.HulkAspectSupport;
import com.mtl.hulk.configuration.HulkProperties;
import com.mtl.hulk.context.*;
import com.mtl.hulk.executor.BusinessActivityExecutor;
import com.mtl.hulk.logger.BusinessActivityLoggerThread;
import com.mtl.hulk.message.HulkErrorCode;
import com.mtl.hulk.model.*;
import com.mtl.hulk.tools.ExecutorUtil;
import com.mtl.hulk.tools.FutureUtil;
import org.aopalliance.intercept.MethodInterceptor;
import org.aopalliance.intercept.MethodInvocation;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationContext;

import java.io.Serializable;
import java.util.concurrent.*;

public class TransactionInterceptor extends HulkAspectSupport implements HulkInterceptor, MethodInterceptor, Serializable {

    private static final Logger logger = LoggerFactory.getLogger(TransactionInterceptor.class);

    private final ExecutorService transactionExecutor = Executors.newFixedThreadPool(properties.getTransactionThreadPoolSize());

    public TransactionInterceptor(HulkProperties properties, ApplicationContext apc) {
        super(properties, apc);
    }

    /**
     * Try请求拦截处理
     * @param methodInvocation
     * @return
     * @throws Throwable
     */
    @Override
    public Object invoke(MethodInvocation methodInvocation) throws Throwable {
        if (!prepareContext(methodInvocation)) {
            return null;
        }

        RuntimeContext context = RuntimeContextHolder.getContext();

        HulkResponse response = null;
        Future<Boolean> future = null;
        ExecutorService loggerExecutor = HulkResourceManager.getBam().getLogExecutor();
        try {
            if (context.getActivity().getId() == null) {
                HulkContext hulkContext = new HulkContext();
                hulkContext.setBac(BusinessActivityContextHolder.getContext());
                hulkContext.setRc(RuntimeContextHolder.getContext());
                return JSONObject.toJSONString(hulkContext);
            }

            boolean status = HulkResourceManager.getBam().start(methodInvocation);
            if (status) {
                RuntimeContextHolder.getContext().getActivity().setStatus(BusinessActivityStatus.TRIED);
                future = transactionExecutor.submit(new BusinessActivityExecutor(new HulkContext(BusinessActivityContextHolder.getContext(),
                        RuntimeContextHolder.getContext())));
                status = future.get(RuntimeContextHolder.getContext().getActivity().getTimeout(), TimeUnit.SECONDS);
            } else {
                RuntimeContextHolder.getContext().getActivity().setStatus(BusinessActivityStatus.TRYING_EXPT);
            }
            loggerExecutor.submit(new BusinessActivityLoggerThread(properties,
                    new HulkContext(BusinessActivityContextHolder.getContext(), RuntimeContextHolder.getContext())));
            response = HulkResponseFactory.getResponse(status);
        } catch (Exception ex) {
            if (RuntimeContextHolder.getContext().getActivity().getStatus() == BusinessActivityStatus.ROLLBACKING_FAILED) {
                logger.error("Transaction Execute Error", ex);
            }
            HulkErrorCode code = HulkErrorCode.RUN_EXCEPTION;
            if (ex instanceof TimeoutException) {
                code = HulkErrorCode.COMMIT_TIMEOUT;
            }
            if (ex instanceof RejectedExecutionException) {
                code = HulkErrorCode.REJECT_EXCEPTION;
            }
            RuntimeContextHolder.getContext().setException(new HulkException(code.getCode(), code.getMessage()));
            HulkResourceManager.getBam().getListener().closeFuture();
            for (HulkInterceptor interceptor : HulkResourceManager.getInterceptors()) {
                interceptor.closeFuture();
            }
            FutureUtil.cancelNow(future);
            response = processException();
        } finally {
            BusinessActivityContextHolder.clearContext();
            RuntimeContextHolder.clearContext();
            FutureUtil.gracefulCancel(future);
        }
        return JSONObject.toJSONString(response);
    }

    private HulkResponse processException() throws Exception {
        boolean status = false;

        Future<Boolean> future = transactionExecutor.submit(new BusinessActivityExecutor(new HulkContext(BusinessActivityContextHolder.getContext(),
                RuntimeContextHolder.getContext())));
        try {
            status = future.get(RuntimeContextHolder.getContext().getActivity().getTimeout(), TimeUnit.SECONDS);
        } catch (Exception ex) {
            throw ex;
        }

        ExecutorService loggerExecutor = HulkResourceManager.getBam().getLogExecutor();
        loggerExecutor.submit(new BusinessActivityLoggerThread(properties,
                new HulkContext(BusinessActivityContextHolder.getContext(), RuntimeContextHolder.getContext())));

        return HulkResponseFactory.getResponse(status);
    }

    private boolean prepareContext(MethodInvocation methodInvocation) {
        MTLDTActivity activityAnnotation = methodInvocation.getMethod().getAnnotation(MTLDTActivity.class);
        MTLTwoPhaseAction transaction = methodInvocation.getMethod().getAnnotation(MTLTwoPhaseAction.class);

        if (transaction == null) {
            return false;
        }

        BusinessActivityContext bac = BusinessActivityContextHolder.getContext();
        RuntimeContext context = RuntimeContextHolder.getContext();
        BusinessActivity activity = new BusinessActivity();

        bac.getParams().put(methodInvocation.getMethod().getName(), methodInvocation.getArguments());
        BusinessActivityContextHolder.setContext(bac);

        if (activityAnnotation != null) {
            BusinessActivityId id = new BusinessActivityId();
            id.setBusinessActivity(activityAnnotation.businessActivity());
            id.setBusinessDomain(activityAnnotation.businessDomain());
            id.setEntityId(activityAnnotation.entityId());
            id.setSequence(String.valueOf(BusinessActivityIdSequenceFactory.getSequence(properties.getTransIdSequence()).nextId()));
            activity.setId(id);
            activity.setTimeout(activityAnnotation.timeout());
            activity.setIsolationLevel(activityAnnotation.isolationLevel());
        }
        activity.setStatus(BusinessActivityStatus.TRYING);

        AtomicAction tryAction = new AtomicAction();
        ServiceOperation tryServiceOperation = new ServiceOperation();
        tryServiceOperation.setName(methodInvocation.getMethod().getName());
        tryServiceOperation.setBeanClass(StringUtils.uncapitalize(methodInvocation.getMethod().getDeclaringClass().getSimpleName()));
        tryServiceOperation.setType(ServiceOperationType.TCC);
        tryAction.setServiceOperation(tryServiceOperation);
        tryAction.setCallType(transaction.callType());
        activity.getAtomicTryActions().add(tryAction);

        AtomicAction confirmAction = new AtomicAction();
        ServiceOperation confirmServiceOperation = new ServiceOperation();
        confirmServiceOperation.setName(transaction.confirmMethod());
        confirmServiceOperation.setService(applicationContext.getId().split(":")[0]);
        confirmServiceOperation.setType(ServiceOperationType.TCC);
        confirmAction.setServiceOperation(confirmServiceOperation);
        confirmAction.setCallType(transaction.callType());
        activity.getAtomicCommitActions().add(confirmAction);

        AtomicAction cancelAction = new AtomicAction();
        ServiceOperation cancelServiceOperation = new ServiceOperation();
        cancelServiceOperation.setName(transaction.cancelMethod());
        cancelServiceOperation.setService(applicationContext.getId().split(":")[0]);
        cancelServiceOperation.setType(ServiceOperationType.TCC);
        cancelAction.setServiceOperation(cancelServiceOperation);
        cancelAction.setCallType(transaction.callType());
        activity.getAtomicRollbackActions().add(cancelAction);

        context.setActivity(activity);
        RuntimeContextHolder.setContext(context);

        return true;
    }

    @Override
    public void destroy() {
        ExecutorUtil.gracefulShutdown(transactionExecutor);
    }

    @Override
    public void destroyNow() {
        ExecutorUtil.shutdownNow(transactionExecutor);
    }

    @Override
    public void closeFuture() {
    }

}