package com.pingo.discovery.router;

@FunctionalInterface
interface HashFunc {

  int hash(String value);
}
