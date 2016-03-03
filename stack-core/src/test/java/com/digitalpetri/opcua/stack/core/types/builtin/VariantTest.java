/*
 * Copyright 2015 Kevin Herron
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.digitalpetri.opcua.stack.core.types.builtin;

import org.testng.annotations.Test;

public class VariantTest {

    @Test
    public void testVariantCanContainVariantArray() {
        new Variant(new Variant[] {new Variant(0), new Variant(1), new Variant(2)});
    }

    @Test(expectedExceptions = IllegalArgumentException.class)
    public void variantCannotContainVariant() {
        new Variant(new Variant(null));
    }

    @Test(expectedExceptions = IllegalArgumentException.class)
    public void variantCannotContainDataValue() {
        new Variant(new DataValue(Variant.NULL_VALUE));
    }

    @Test(expectedExceptions = IllegalArgumentException.class)
    public void variantCannotContainDiagnosticInfo() {
        new Variant(DiagnosticInfo.NULL_VALUE);
    }

}
