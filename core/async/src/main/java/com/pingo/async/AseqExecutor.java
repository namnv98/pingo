package com.pingo.async;

import com.pingo.core.common.comp.LifeCycle;

public interface AseqExecutor extends LifeCycle {

    void submit(AsyncRunnable task);
}
