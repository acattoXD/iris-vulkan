package net.irisshaders.iris.vulkan;

/** Pure ownership plan for three logical depths backed by at most four separate images. */
public final class IrisVulkanDepthSlotPlan {
    public static final int LOGICAL_COUNT = 3;
    public static final int OWNER_COUNT = 4;
    private static final int OWNER_MASK = (1 << OWNER_COUNT) - 1;
    private final int[] owners = {0, 1, 2};

    /** Restore independent slot identities before initialization; this never owns/closes GPU objects. */
    public void reset() { for (int i = 0; i < LOGICAL_COUNT; i++) owners[i] = i; }

    public int readOwner(int logicalSlot) { checkLogical(logicalSlot); return owners[logicalSlot]; }

    /** Stable image used for the common initial value after reset; all three owners remain allocated. */
    public int chooseInitialClearOwner() { return 1; }

    /** Publish the common clear value only after that full-image clear succeeds. */
    public void commitInitialClear(int physicalOwner) { commitOpaque(physicalOwner); }

    /** All three values will be replaced together from the external native depth image. */
    public int chooseOpaqueOwner() { return owners[1]; }

    /** Publish aliases only after the one complete conversion has been recorded successfully. */
    public void commitOpaque(int physicalOwner) {
        checkOwner(physicalOwner);
        for (int i = 0; i < LOGICAL_COUNT; i++) owners[i] = physicalOwner;
    }

    /**
     * Return an image that can be fully overwritten without touching another live
     * logical value or any sampled input. The current logical value may be reused
     * only when unshared and not sampled. No copy of prior destination data is needed.
     */
    public int chooseWriteOwner(int logicalSlot, int sampledOwnerMask) {
        int forbidden = forbiddenOwners(logicalSlot, sampledOwnerMask);
        int current = owners[logicalSlot];
        if ((forbidden & (1 << current)) == 0) return current;
        for (int owner = 0; owner < OWNER_COUNT; owner++) if ((forbidden & (1 << owner)) == 0) return owner;
        throw new IllegalStateException("No independent image available for full depth write");
    }

    /** Recheck the write contract before publishing a newly recorded logical value. */
    public void commitWrite(int logicalSlot, int physicalOwner, int sampledOwnerMask) {
        checkOwner(physicalOwner);
        if ((forbiddenOwners(logicalSlot, sampledOwnerMask) & (1 << physicalOwner)) != 0)
            throw new IllegalArgumentException("Depth output aliases another logical value or a sampled input");
        owners[logicalSlot] = physicalOwner;
    }

    /** Diagnostic snapshot, not used on the render hot path. */
    public int[] logicalOwners() { return owners.clone(); }

    private int forbiddenOwners(int logicalSlot, int sampledOwnerMask) {
        checkLogical(logicalSlot);
        if ((sampledOwnerMask & ~OWNER_MASK) != 0) throw new IllegalArgumentException("Invalid sampled-owner mask");
        int forbidden = sampledOwnerMask;
        for (int i = 0; i < LOGICAL_COUNT; i++) if (i != logicalSlot) forbidden |= 1 << owners[i];
        return forbidden;
    }

    private static void checkLogical(int slot) {
        if (slot < 0 || slot >= LOGICAL_COUNT) throw new IllegalArgumentException("Invalid logical depth slot " + slot);
    }
    private static void checkOwner(int owner) {
        if (owner < 0 || owner >= OWNER_COUNT) throw new IllegalArgumentException("Invalid physical depth owner " + owner);
    }
}
