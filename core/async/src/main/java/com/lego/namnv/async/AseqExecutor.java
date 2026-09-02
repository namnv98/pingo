package com.lego.namnv.async;

import com.lego.namnv.core.common.comp.LifeCycle;

public interface AseqExecutor extends LifeCycle {

    void submit(AsyncRunnable task);
}
