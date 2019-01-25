/*
 * Copyright 2019 dorkbox, llc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package dorkbox.network.rmi;

/**
 *
 */
public
class MessageWithTestCow {
    public int number;
    public String text;
    private TestCow testCow;

    private
    MessageWithTestCow() {
        // for kryo
    }

    public
    MessageWithTestCow(final TestCow test) {
        testCow = test;
    }

    public
    TestCow getTestCow() {
        return testCow;
    }
}
