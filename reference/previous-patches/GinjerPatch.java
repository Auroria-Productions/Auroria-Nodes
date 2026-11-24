import org.objectweb.asm.*;

final class GinjerPatch {
    private static final String WAR = "phonon/nodes/imported/GinjerWar";
    private static final String PORT = "phonon/nodes/imported/GinjerPorts";
    private static final String OBJECTS = "Lphonon/nodes/objects/";
    private static final String FLAG = "Lphonon/nodes/war/FlagWar;";
    private static final String PLAYER = "Lorg/bukkit/entity/Player;";

    static byte[] patch(byte[] bytes) {
        var reader = new ClassReader(bytes);
        String owner = reader.getClassName();
        if (!owner.equals(WAR) && !owner.equals("phonon/nodes/war/FlagWar")
            && !owner.equals("phonon/nodes/listeners/NodesWorldListener") && !owner.equals("phonon/nodes/Nodes")
            && !owner.equals("phonon/nodes/commands/PortCommandKt")
            && !owner.equals("phonon/nodes/tasks/PortWarpTask$start$runnable$1")) return bytes;

        var writer = new ClassWriter(reader, ClassWriter.COMPUTE_MAXS);
        reader.accept(new ClassVisitor(Opcodes.ASM9, writer) {
            @Override public MethodVisitor visitMethod(int access, String name, String desc, String sig, String[] exceptions) {
                if (owner.equals(WAR) && (name.equals("boxId") || name.equals("cutOff"))) {
                    var m = super.visitMethod(access & ~Opcodes.ACC_NATIVE, name, desc, sig, exceptions);
                    m.visitCode();
                    boolean cut = name.equals("cutOff");
                    if (cut) {
                        m.visitFieldInsn(Opcodes.GETSTATIC, "phonon/nodes/supply/SupplyLines", "INSTANCE", "Lphonon/nodes/supply/SupplyLines;");
                        m.visitVarInsn(Opcodes.ALOAD, 0);
                    }
                    m.visitVarInsn(Opcodes.ALOAD, cut ? 1 : 0);
                    m.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "phonon/nodes/objects/Territory", "getId-L9_GXOM", "()I", false);
                    if (cut) {
                        m.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "phonon/nodes/supply/SupplyLines", "isCutOff-4_jhAC4", "(" + OBJECTS + "Town;I)Z", false);
                    } else {
                        m.visitMethodInsn(Opcodes.INVOKESTATIC, "phonon/nodes/objects/TerritoryId", "box-impl", "(I)" + OBJECTS + "TerritoryId;", false);
                    }
                    m.visitInsn(cut ? Opcodes.IRETURN : Opcodes.ARETURN);
                    m.visitMaxs(0, 0);
                    m.visitEnd();
                    return null;
                }
                var m = super.visitMethod(access, name, desc, sig, exceptions);
                boolean income = owner.equals("phonon/nodes/Nodes") && name.equals("runIncome");
                boolean listener = owner.endsWith("/NodesWorldListener") && (name.equals("onBlockPlace") || name.equals("onBlockInteract"));
                boolean beginAttack = owner.endsWith("/FlagWar") && name.startsWith("beginAttack-");
                boolean finish = owner.endsWith("/FlagWar") && name.equals("finishAttack$nodes");
                boolean capture = owner.endsWith("/FlagWar") && name.equals("computeChunkAttackTimeTicks");
                boolean warp = owner.endsWith("/PortCommandKt") && name.equals("doPortWarp");
                boolean arrive = owner.contains("/PortWarpTask$") && name.contains("lambda");
                if (!(income || listener || beginAttack || finish || capture || warp || arrive)) return m;
                return new MethodVisitor(Opcodes.ASM9, m) {
                    @Override public void visitCode() {
                        super.visitCode();
                        if (income) call(WAR, "beginIncome", "()V");
                    }

                    @Override public void visitInsn(int opcode) {
                        if (income && opcode == Opcodes.RETURN) call(WAR, "endIncome", "()V");
                        if (capture && opcode == Opcodes.LRETURN) {
                            load(1); load(2);
                            call(WAR, "captureTicks", "(J" + OBJECTS + "TerritoryChunk;" + OBJECTS + "Town;)J");
                        }
                        super.visitInsn(opcode);
                    }

                    @Override public void visitMethodInsn(int opcode, String cls, String method, String signature, boolean itf) {
                        if (listener && cls.endsWith("/FlagWar") && method.equals("getEnabled$nodes")) {
                            load(6);
                            call(WAR, "flagsAllowed", "(" + FLAG + OBJECTS + "TerritoryChunk;)Z");
                        } else if (beginAttack && cls.endsWith("/FlagWar") && method.equals("chunkIsEnemy$nodes")) {
                            call(WAR, "enemy", "(" + FLAG + signature.substring(1));
                        } else if (warp && cls.endsWith("/Port") && method.equals("isPublic")) {
                            load(0);
                            call(PORT, "isPublic", "(" + OBJECTS + "Port;" + PLAYER + ")Z");
                        } else if (warp && cls.equals("phonon/nodes/Nodes") && method.equals("getRelationshipOfPlayerToTown")) {
                            call(PORT, "relationship", "(Lphonon/nodes/Nodes;" + signature.substring(1));
                        } else if (arrive && cls.endsWith("/PortCommandKt") && method.equals("warpToPort")) {
                            load(0);
                            call(PORT, "arrive", signature.substring(0, signature.indexOf(')')) + "Lphonon/nodes/tasks/PortWarpTask;)V");
                        } else if (arrive && cls.equals("phonon/nodes/Message") && method.equals("announcement") && skipArrivalAnnouncement) {
                            super.visitInsn(Opcodes.POP);
                            super.visitInsn(Opcodes.POP2);
                            skipArrivalAnnouncement = false;
                        } else {
                            super.visitMethodInsn(opcode, cls, method, signature, itf);
                            if (finish && cls.endsWith("/WarzoneTownRegistry") && method.equals("isWarzoneTown$nodes") && ++homeChecks == 2)
                                call(WAR, "skipInstantAnnex", "(Z)Z");
                            if (income && cls.endsWith("$TerritoryResourceSnapshot") && method.equals("getIncome")) {
                                load(11); load(6);
                                call(WAR, "income", "(Ljava/util/EnumMap;" + OBJECTS + "Territory;" + OBJECTS + "Town;)Ljava/util/EnumMap;");
                            }
                            if (warp && cls.endsWith("/PortCommandKt") && method.equals("getPortWarpTime")) {
                                load(0);
                                call(PORT, "warpTime", "(D" + PLAYER + ")D");
                            }
                        }
                        if (arrive && cls.endsWith("/PortCommandKt") && method.equals("warpToPort")) skipArrivalAnnouncement = true;
                    }
                    private int homeChecks;
                    private boolean skipArrivalAnnouncement;
                    private void load(int slot) { super.visitVarInsn(Opcodes.ALOAD, slot); }
                    private void call(String cls, String method, String signature) {
                        super.visitMethodInsn(Opcodes.INVOKESTATIC, cls, method, signature, false);
                    }
                };
            }
        }, 0);
        return writer.toByteArray();
    }
}
