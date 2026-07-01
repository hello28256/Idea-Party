declare module 'ali-oss' {
  // ali-oss 没官方 .d.ts(用 JS 写的),这里只声明我们用到的最小 API
  // 实际签名:new OSS({ accessKeyId, accessKeySecret, stsToken?, bucket, region, endpoint?, secure? })
  //          .put(key, file): Promise<{ url, name, res }>
  export default class OSS {
    constructor(options: {
      accessKeyId: string
      accessKeySecret: string
      stsToken?: string
      bucket: string
      region: string
      endpoint?: string
      secure?: boolean
    })
    put(key: string, file: Blob | File | Buffer | string): Promise<{ url: string; name: string; res: unknown }>
  }
}
