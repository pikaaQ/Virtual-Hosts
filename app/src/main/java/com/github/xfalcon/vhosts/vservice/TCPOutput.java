/*
** Copyright 2015, Mohamed Naufal
**
** Licensed under the Apache License, Version 2.0 (the "License");
** you may not use this file except in compliance with the License.
** You may obtain a copy of the License at
**
**     http://www.apache.org/licenses/LICENSE-2.0
**
** Unless required by applicable law or agreed to in writing, software
** distributed under the License is distributed on an "AS IS" BASIS,
** WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
** See the License for the specific language governing permissions and
** limitations under the License.
*/

package com.github.xfalcon.vhosts.vservice;


import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.SocketChannel;
import java.util.Random;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.locks.ReentrantLock;

import com.github.xfalcon.vhosts.util.LogUtils;
import com.github.xfalcon.vhosts.vservice.Packet.TCPHeader;
import com.github.xfalcon.vhosts.vservice.TCB.TCBStatus;

public class TCPOutput implements Runnable
{
    private static final String TAG = TCPOutput.class.getSimpleName();

    private VhostsService vpnService;
    private ConcurrentLinkedQueue<Packet> inputQueue;
    private ConcurrentLinkedQueue<ByteBuffer> outputQueue;
    private Selector selector;
    private ReentrantLock tcpSelectorLock;

    private Random random = new Random();
    public TCPOutput(ConcurrentLinkedQueue<Packet> inputQueue, ConcurrentLinkedQueue<ByteBuffer> outputQueue,
                     Selector selector,ReentrantLock tcpSelectorLock, VhostsService vpnService)
    {
        this.inputQueue = inputQueue;
        this.outputQueue = outputQueue;
        this.selector = selector;
        this.vpnService = vpnService;
        this.tcpSelectorLock=tcpSelectorLock;
    }

    @Override
    public void run()
    {
        LogUtils.i(TAG, "Started");
        try
        {

            while (!Thread.interrupted()) {

                Packet currentPacket = inputQueue.poll();
                if (currentPacket == null){
                    Thread.sleep(11);
                    continue;
                }

                ByteBuffer payloadBuffer = currentPacket.backingBuffer;
                currentPacket.backingBuffer = null;
                ByteBuffer responseBuffer = ByteBufferPool.acquire();

                InetAddress destinationAddress = currentPacket.ipHeader.destinationAddress;

                TCPHeader tcpHeader = currentPacket.tcpHeader;
                int destinationPort = tcpHeader.destinationPort;
                int sourcePort = tcpHeader.sourcePort;

                String ipAndPort = destinationAddress.getHostAddress() + ":" +
                        destinationPort + ":" + sourcePort;
                TCB tcb = TCB.getTCB(ipAndPort);
                if (tcb == null)
                    initializeConnection(ipAndPort, destinationAddress, destinationPort,
                            currentPacket, tcpHeader, responseBuffer);
                else if (tcpHeader.isSYN())
                    processDuplicateSYN(tcb, tcpHeader, responseBuffer);
                else if (tcpHeader.isRST())
                    closeCleanly(tcb, responseBuffer);
                else if (tcpHeader.isFIN())
                    processFIN(tcb, tcpHeader, responseBuffer);
                else if (tcpHeader.isACK())
                    processACK(tcb, tcpHeader, payloadBuffer, responseBuffer);

                // XXX: cleanup later
                if (responseBuffer.position() == 0)
                    ByteBufferPool.release(responseBuffer);
                ByteBufferPool.release(payloadBuffer);
            }
        }
        catch (InterruptedException e)
        {
            LogUtils.i(TAG, "Stopping");
        }
        catch (IOException e)
        {
            LogUtils.e(TAG, e.toString(), e);
        }
        finally
        {
            TCB.closeAll();
        }
    }

    private void initializeConnection(String ipAndPort, InetAddress destinationAddress, int destinationPort,
                                      Packet currentPacket, TCPHeader tcpHeader, ByteBuffer responseBuffer)
            throws IOException
    {
        currentPacket.swapSourceAndDestination();
        if (tcpHeader.isSYN())
        {
            // 检查是否为DNS请求（端口53）
            if (destinationPort == 53) {
                // 对于TCP DNS，我们需要阻止连接，而不是让连接通过自定义DNS
                // 这里需要根据域名后缀过滤决定是否允许DNS连接
                String domainSuffixes = vpnService.getDomainSuffixes();
                boolean isCustomDnsEnabled = vpnService.isCustomDnsEnabled();
                
                if (isCustomDnsEnabled && !shouldAllowTcpDnsConnection(ipAndPort, domainSuffixes)) {
                    // 如果使用自定义DNS且域名后缀不匹配，发送RST包拒绝连接
                    currentPacket.updateTCPBuffer(responseBuffer, (byte) TCPHeader.RST, 0, tcpHeader.sequenceNumber + 1, 0);
                    outputQueue.offer(responseBuffer);
                    return;
                }
            }
            
            SocketChannel outputChannel = SocketChannel.open();
            outputChannel.configureBlocking(false);
            vpnService.protect(outputChannel.socket());

            TCB tcb = new TCB(ipAndPort, random.nextInt(Short.MAX_VALUE + 1), tcpHeader.sequenceNumber, tcpHeader.sequenceNumber + 1,
                    tcpHeader.acknowledgementNumber, outputChannel, currentPacket);
            TCB.putTCB(ipAndPort, tcb);

            try
            {
                outputChannel.connect(new InetSocketAddress(destinationAddress, destinationPort));
                if (outputChannel.finishConnect())
                {
                    tcb.status = TCBStatus.SYN_RECEIVED;
                    // TODO: Set MSS for receiving larger packets from the device
                    currentPacket.updateTCPBuffer(responseBuffer, (byte) (TCPHeader.SYN | TCPHeader.ACK),
                            tcb.mySequenceNum, tcb.myAcknowledgementNum, 0);
                    tcb.mySequenceNum++; // SYN counts as a byte
                }
                else
                {
                    tcb.status = TCBStatus.SYN_SENT;
                    tcpSelectorLock.lock();
                    selector.wakeup();
                    tcb.selectionKey = outputChannel.register(selector, SelectionKey.OP_CONNECT, tcb);
                    tcpSelectorLock.unlock();
                    return;
                }
            }
            catch (IOException e)
            {
                LogUtils.e(TAG, "Connection error: " + ipAndPort, e);
                currentPacket.updateTCPBuffer(responseBuffer, (byte) TCPHeader.RST, 0, tcb.myAcknowledgementNum, 0);
                TCB.closeTCB(tcb);
            }
        }
        else
        {
            currentPacket.updateTCPBuffer(responseBuffer, (byte) TCPHeader.RST,
                    0, tcpHeader.sequenceNumber + 1, 0);
        }
        outputQueue.offer(responseBuffer);
    }
    
    // 对TCP DNS连接的简化处理 - 根据IP地址判断是否允许
    private boolean shouldAllowTcpDnsConnection(String ipAndPort, String domainSuffixes) {
        // 如果没有设置域名后缀，则允许所有DNS连接
        if (domainSuffixes == null || domainSuffixes.trim().isEmpty()) {
            return true;
        }
        // 对于TCP DNS，我们无法直接获取域名（因为域名在DNS查询数据中）
        // 在当前实现中，我们无法在TCP连接建立阶段知道域名
        // 所以对于TCP DNS，允许连接建立，但实际DNS查询会在processACK中被处理
        // 并且实际的DNS查询会通过UDP路径处理（因为我们的VPN会拦截所有DNS查询）
        // 对TCP连接的处理主要是在processACK方法中
        return true;
    }

    private void processDuplicateSYN(TCB tcb, TCPHeader tcpHeader, ByteBuffer responseBuffer)
    {
        synchronized (tcb)
        {
            if (tcb.status == TCBStatus.SYN_SENT)
            {
                tcb.myAcknowledgementNum = tcpHeader.sequenceNumber + 1;
                return;
            }
        }
        sendRST(tcb, 1, responseBuffer);
    }

    private void processFIN(TCB tcb, TCPHeader tcpHeader, ByteBuffer responseBuffer)
    {
        synchronized (tcb)
        {
            Packet referencePacket = tcb.referencePacket;
            tcb.myAcknowledgementNum = tcpHeader.sequenceNumber + 1;
            tcb.theirAcknowledgementNum = tcpHeader.acknowledgementNumber;

            if (tcb.waitingForNetworkData)
            {
                tcb.status = TCBStatus.CLOSE_WAIT;
                referencePacket.updateTCPBuffer(responseBuffer, (byte) TCPHeader.ACK,
                        tcb.mySequenceNum, tcb.myAcknowledgementNum, 0);
            }
            else
            {
                tcb.status = TCBStatus.LAST_ACK;
                referencePacket.updateTCPBuffer(responseBuffer, (byte) (TCPHeader.FIN | TCPHeader.ACK),
                        tcb.mySequenceNum, tcb.myAcknowledgementNum, 0);
                tcb.mySequenceNum++; // FIN counts as a byte
            }
        }
        outputQueue.offer(responseBuffer);
    }

    private void processACK(TCB tcb, TCPHeader tcpHeader, ByteBuffer payloadBuffer, ByteBuffer responseBuffer) throws IOException
    {
        int payloadSize = payloadBuffer.limit() - payloadBuffer.position();

        synchronized (tcb)
        {
            SocketChannel outputChannel = tcb.channel;
            if (tcb.status == TCBStatus.SYN_RECEIVED)
            {
                tcb.status = TCBStatus.ESTABLISHED;
                tcpSelectorLock.lock();
                selector.wakeup();
                tcb.selectionKey = outputChannel.register(selector, SelectionKey.OP_READ, tcb);
                tcpSelectorLock.unlock();
                tcb.waitingForNetworkData = true;
            }
            else if (tcb.status == TCBStatus.LAST_ACK)
            {
                closeCleanly(tcb, responseBuffer);
                return;
            }

            if (payloadSize == 0) return; // Empty ACK, ignore

            // 检查是否为DNS端口（53）
            if (tcb.referencePacket.ipHeader.destinationAddress != null && tcb.referencePacket.tcpHeader.destinationPort == 53) {
                String domainSuffixes = vpnService.getDomainSuffixes();
                boolean isCustomDnsEnabled = vpnService.isCustomDnsEnabled();
                
                // 对于TCP DNS，如果我们启用了域名后缀过滤，需要解析DNS查询
                if (isCustomDnsEnabled && domainSuffixes != null && !domainSuffixes.trim().isEmpty()) {
                    // TCP DNS包格式：前两个字节是长度，然后是DNS查询数据
                    if (payloadSize >= 2) {
                        // 从payloadBuffer中获取DNS查询数据
                        // 注意：需要复制缓冲区以安全地读取DNS数据
                        payloadBuffer.mark();
                        payloadBuffer.position(payloadBuffer.position() - payloadSize); // 回到数据开始位置
                        byte[] data = new byte[payloadSize];
                        payloadBuffer.get(data);
                        payloadBuffer.reset(); // 恢复位置
                        
                        // 重置缓冲区位置以继续写入
                        payloadBuffer.position(payloadBuffer.position() - payloadSize);
                        
                        // 检查是否应该处理这个DNS查询
                        if (!shouldProcessTcpDnsQuery(data, domainSuffixes)) {
                            // 如果不应该处理，仍然转发到原始DNS服务器，以确保查询可以正常解析
                            // 这样可以保证不匹配后缀的域名使用系统DNS正常解析
                        }
                    }
                }
            }

            if (!tcb.waitingForNetworkData)
            {
                selector.wakeup();
                tcb.selectionKey.interestOps(SelectionKey.OP_READ);
                tcb.waitingForNetworkData = true;
            }

            // Forward to remote server
            try
            {
                while (payloadBuffer.hasRemaining())
                    outputChannel.write(payloadBuffer);
            }
            catch (IOException e)
            {
                LogUtils.e(TAG, "Network write error: " + tcb.ipAndPort, e);
                sendRST(tcb, payloadSize, responseBuffer);
                return;
            }

            // TODO: We don't expect out-of-order packets, but verify
            tcb.myAcknowledgementNum = tcpHeader.sequenceNumber + payloadSize;
            tcb.theirAcknowledgementNum = tcpHeader.acknowledgementNumber;
            Packet referencePacket = tcb.referencePacket;
            referencePacket.updateTCPBuffer(responseBuffer, (byte) TCPHeader.ACK, tcb.mySequenceNum, tcb.myAcknowledgementNum, 0);
        }
        outputQueue.offer(responseBuffer);
    }
    
    private boolean shouldProcessTcpDnsQuery(byte[] dnsData, String domainSuffixes) {
        // TCP DNS查询的前两个字节是长度字段，真正的DNS查询从第3个字节开始
        if (dnsData == null || dnsData.length < 14) { // DNS头部最小长度
            return true; // 如果数据不足，允许通过
        }
        
        try {
            // 跳过TCP DNS的长度字段（前2字节）
            byte[] dnsQuery = new byte[dnsData.length - 2];
            System.arraycopy(dnsData, 2, dnsQuery, 0, dnsQuery.length);
            
            // 使用Message类解析DNS查询包
            org.xbill.DNS.Message dnsMessage = new org.xbill.DNS.Message(dnsQuery);
            org.xbill.DNS.Record question = dnsMessage.getQuestion();
            
            if (question != null) {
                String queryDomain = question.getName().toString();
                
                // 使用DnsChange类中的域名过滤逻辑
                String[] suffixes = domainSuffixes.split(",");
                for (String suffix : suffixes) {
                    suffix = suffix.trim();
                    if (suffix.isEmpty()) continue;
                    
                    // 检查域名是否以指定后缀结尾
                    if (queryDomain.endsWith(suffix)) {
                        return true; // 匹配后缀，允许处理
                    }
                }
                
                // 不匹配任何后缀，不允许处理
                return false;
            }
        } catch (Exception e) {
            LogUtils.e(TAG, "Error parsing TCP DNS query: ", e);
            // 如果解析出错，允许通过以避免中断连接
        }
        
        // 不匹配任何后缀或解析失败，不允许处理
        return false;
    }

    private void sendRST(TCB tcb, int prevPayloadSize, ByteBuffer buffer)
    {
        tcb.referencePacket.updateTCPBuffer(buffer, (byte) TCPHeader.RST, 0, tcb.myAcknowledgementNum + prevPayloadSize, 0);
        outputQueue.offer(buffer);
        TCB.closeTCB(tcb);
    }

    private void closeCleanly(TCB tcb, ByteBuffer buffer)
    {
        ByteBufferPool.release(buffer);
        TCB.closeTCB(tcb);
    }
}
