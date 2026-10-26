// Role matrix (mirrors server requireRole checks).
export const CAN = {
  reports: ['MANAGER', 'INVENTORY'],
  items: ['MANAGER', 'INVENTORY'],
  pos: ['MANAGER', 'CASHIER'],
  orders: ['MANAGER', 'INVENTORY', 'AGENT'],
  orderActions: ['MANAGER', 'INVENTORY'],
  stores: ['MANAGER', 'INVENTORY'],
  map: ['MANAGER'],
  admin: ['MANAGER']
};
export const can = (user, cap) => !!user && CAN[cap].includes(user.role);
export const homePath = (user) => (user?.role === 'AGENT' ? '/orders' : can(user, 'reports') ? '/' : '/pos');
