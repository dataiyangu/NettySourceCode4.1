/*
 * Copyright 2012 The Netty Project
 *
 * The Netty Project licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */
package io.netty.testsuite.transport.socket;

import io.netty.bootstrap.Bootstrap;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.buffer.ByteBufAllocator;
import io.netty.channel.ChannelOption;
import io.netty.testsuite.transport.AbstractComboTestsuiteTest;
import io.netty.testsuite.transport.TestsuitePermutation;
import io.netty.testsuite.util.TestUtils;
import io.netty.util.NetUtil;

import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.List;

public abstract class AbstractSocketTest extends AbstractComboTestsuiteTest<ServerBootstrap, Bootstrap> {

    protected volatile SocketAddress addr;

    protected AbstractSocketTest() {
        super(ServerBootstrap.class, Bootstrap.class);
    }

    @Override
    protected List<TestsuitePermutation.BootstrapComboFactory<ServerBootstrap, Bootstrap>> newFactories() {
        return SocketTestPermutation.INSTANCE.socket();
    }

    @Override
    protected void configure(ServerBootstrap bootstrap, Bootstrap bootstrap2, ByteBufAllocator allocator) {
        addr = newSocketAddress();
        bootstrap.localAddress(addr);
        bootstrap.option(ChannelOption.ALLOCATOR, allocator);
        bootstrap.childOption(ChannelOption.ALLOCATOR, allocator);
        bootstrap2.remoteAddress(addr);
        bootstrap2.option(ChannelOption.ALLOCATOR, allocator);
    }

    protected SocketAddress newSocketAddress() {
        return new InetSocketAddress(
                NetUtil.LOCALHOST, TestUtils.getFreePort());
    }
}
