package com.lego.namnv.cache;

import java.util.concurrent.Executor;

public class SameThreadExecutor implements Executor {

	@Override
	public void execute(Runnable command) {
		command.run();
	}
}
