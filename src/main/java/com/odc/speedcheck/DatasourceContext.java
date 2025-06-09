package com.odc.speedcheck;

class DatasourceContext {
    private static final ThreadLocal<Integer> DS = new ThreadLocal<>();

    static void set(int id) {
        DS.set(id);
    }

    static int get() {
        return DS.get();
    }
}
