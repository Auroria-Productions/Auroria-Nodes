import org.objectweb.asm.*;

final class PerformancePatch {
    private PerformancePatch() {}

    static byte[] patch(byte[] bytes) {
        var reader = new ClassReader(bytes);
        String name = reader.getClassName();
        boolean nestedWrites = name.equals("phonon/nodes/core/CoreWar") || name.equals("phonon/nodes/core/CoreEconomy");
        boolean poiData = name.equals("phonon/nodes/poi/PoiData");
        boolean poiScan = name.equals("phonon/nodes/poi/Pois");
        boolean coreRules = name.equals("phonon/nodes/core/CoreRules");
        boolean coreHooks = name.equals("phonon/nodes/core/CoreHooks");
        if (!nestedWrites && !coreRules && !coreHooks && !poiData && !poiScan) return bytes;

        var writer = new ClassWriter(reader, ClassWriter.COMPUTE_MAXS);
        reader.accept(new ClassVisitor(Opcodes.ASM9, writer) {
            @Override
            public MethodVisitor visitMethod(int flags, String method, String signature, String generics, String[] exceptions) {
                var out = super.visitMethod(flags, method, signature, generics, exceptions);
                if (coreHooks && method.equals("worldEvent") && signature.equals("(Lorg/bukkit/event/Event;)Z")) {
                    out.visitCode();
                    out.visitVarInsn(Opcodes.ALOAD, 0);
                    out.visitMethodInsn(Opcodes.INVOKESTATIC, "phonon/nodes/core/FastPaths", "worldEvent", signature, false);
                    out.visitInsn(Opcodes.IRETURN);
                    out.visitMaxs(1, 1);
                    out.visitEnd();
                    return null;
                }
                if (coreHooks && method.equals("accelerate") && signature.equals("(Ljava/lang/Object;)V")) {
                    out.visitCode();
                    out.visitVarInsn(Opcodes.ALOAD, 0);
                    out.visitMethodInsn(Opcodes.INVOKESTATIC, "phonon/nodes/core/FastPaths", "accelerate", signature, false);
                    out.visitInsn(Opcodes.RETURN);
                    out.visitMaxs(1, 1);
                    out.visitEnd();
                    return null;
                }
                if (coreRules && method.equals("flagged")) {
                    out.visitCode();
                    out.visitVarInsn(Opcodes.ALOAD, 1);
                    out.visitMethodInsn(Opcodes.INVOKESTATIC, "phonon/nodes/core/FastPaths", "flagged", signature, false);
                    out.visitInsn(Opcodes.IRETURN);
                    out.visitMaxs(1, 2);
                    out.visitEnd();
                    return null;
                }
                if (poiData && (method.equals("load") || method.equals("savePois"))) {
                    return new MethodVisitor(Opcodes.ASM9, out) {
                        @Override public void visitCode() {
                            super.visitCode();
                            super.visitMethodInsn(Opcodes.INVOKESTATIC, "phonon/nodes/poi/PoiIndex", "invalidate", "()V", false);
                        }
                    };
                }
                if (poiScan && method.equals("check") && signature.equals("()V")) {
                    return new MethodVisitor(Opcodes.ASM9, out) {
                        @Override public void visitMethodInsn(int op, String owner, String name, String desc, boolean itf) {
                            if (owner.equals("phonon/nodes/poi/PoiData") && name.equals("pois")) {
                                super.visitMethodInsn(Opcodes.INVOKESTATIC, "phonon/nodes/poi/PoiIndex", "points",
                                    "(Lphonon/nodes/poi/PoiData;)Ljava/util/List;", false);
                                return;
                            }
                            if (owner.equals("java/util/List") && name.equals("iterator")) {
                                super.visitVarInsn(Opcodes.ALOAD, 7);
                                super.visitMethodInsn(Opcodes.INVOKESTATIC, "phonon/nodes/poi/PoiIndex", "candidates",
                                    "(Ljava/util/List;Lorg/bukkit/Location;)Ljava/util/List;", false);
                            }
                            super.visitMethodInsn(op, owner, name, desc, itf);
                        }
                    };
                }
                if (!nestedWrites) return out;
                return new MethodVisitor(Opcodes.ASM9, out) {
                    @Override
                    public void visitMethodInsn(int op, String owner, String target, String desc, boolean isInterface) {
                        if (owner.equals("org/bukkit/configuration/ConfigurationSection") &&
                            target.equals("set") && desc.equals("(Ljava/lang/String;Ljava/lang/Object;)V")) {
                            super.visitMethodInsn(Opcodes.INVOKESTATIC, "phonon/nodes/core/CoreData", "setSection",
                                "(Lorg/bukkit/configuration/ConfigurationSection;Ljava/lang/String;Ljava/lang/Object;)V", false);
                        } else {
                            super.visitMethodInsn(op, owner, target, desc, isInterface);
                        }
                    }
                };
            }
        }, 0);
        return writer.toByteArray();
    }
}
