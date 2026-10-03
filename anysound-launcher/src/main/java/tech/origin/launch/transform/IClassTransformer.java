/*
 * Copyright (c) 2022 - 2026, Origin Technology. All rights reserved.
 */

package tech.origin.launch.transform;

public interface IClassTransformer {
    byte[] transform(String name, byte[] bytes);
}
