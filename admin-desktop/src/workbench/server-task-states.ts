// Generated from ApplicationTasks.State by packaging/update-task-contract.mjs.
export const serverTaskStates=["QUEUED","RUNNING","COMMITTING","SUCCEEDED","FAILED","UNKNOWN","CANCELLED","WAITING_FOR_INPUT","EXPIRED"] as const;
export type ServerTaskState=typeof serverTaskStates[number];
