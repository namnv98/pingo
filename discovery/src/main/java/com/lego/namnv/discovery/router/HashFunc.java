package com.lego.namnv.discovery.router;

@FunctionalInterface
interface HashFunc {

  int hash(String value);
}
