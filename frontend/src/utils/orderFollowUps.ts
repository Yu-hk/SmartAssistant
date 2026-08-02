const ORDER_ID = /\bORD-[A-Z0-9][A-Z0-9_-]{2,63}\b/gi;

function uniqueOrderIds(content: string): string[] {
  return [...new Set((content.match(ORDER_ID) || []).map(value => value.toUpperCase()))];
}

/**
 * Returns up to three safe, conversational next steps for a single-order
 * detail response. Suggestions ask for information or guidance and do not
 * directly execute payment, cancellation, refund, or address changes.
 */
export function getOrderFollowUpSuggestions(content: string): string[] {
  const orderIds = uniqueOrderIds(content);
  if (orderIds.length !== 1) return [];

  const orderId = orderIds[0];
  const normalized = content.replace(/\s+/g, '');

  if (/退款|退货|售后|退款中|退款成功/.test(normalized)) {
    return [
      `查询${orderId}的退款进度`,
      '退款预计多久到账',
      '需要转接人工客服',
    ];
  }

  if (/待付款|未付款|等待付款/.test(normalized)) {
    return [
      `查看${orderId}支持的支付方式`,
      '这个订单可以保留多久',
      `了解如何取消${orderId}`,
    ];
  }

  if (/待发货|未发货|备货中|等待发货/.test(normalized)) {
    return [
      `查看${orderId}预计发货时间`,
      `催促${orderId}尽快发货`,
      '了解如何修改收货信息',
    ];
  }

  if (/已签收|已完成|交易完成|已送达/.test(normalized)) {
    return [
      `了解${orderId}的售后政策`,
      `查询${orderId}的电子发票`,
      '商品有问题怎么办',
    ];
  }

  if (/已发货|运输中|配送中|物流|快递|揽收|派送/.test(normalized)) {
    return [
      `查看${orderId}的完整物流轨迹`,
      '这个订单预计什么时候送达',
      '物流长时间未更新怎么办',
    ];
  }

  if (/订单状态|订单详情|订单信息/.test(normalized)) {
    return [
      `查看${orderId}的物流进度`,
      `了解${orderId}的售后政策`,
      `查询${orderId}的电子发票`,
    ];
  }

  return [];
}
