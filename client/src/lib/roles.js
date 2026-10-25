// Role matrix (mirrors server requireRole checks).
export const CAN = {
  reports: ['MANAGER', 'INVENTORY'],
  items: ['MANAGER', 'INVENTORY'],
  pos: ['MANAGER', 'CASHIER', 'AGENT'],
  admin: ['MANAGER']
};
export const can = (user, cap) => !!user && CAN[cap].includes(user.role);
export const homePath = (user) => (can(user, 'reports') ? '/' : '/pos');
