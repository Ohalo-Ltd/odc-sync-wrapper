package com.odc.syncwrapper;

class DatasourceContext {
    private static final ThreadLocal<Integer> DS = new ThreadLocal<>();

    static void set(int id) {
        DS.set(id);
    }

    static int get() {
        return DS.get();
    }
}
