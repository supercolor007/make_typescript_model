Make Java model to TypeScript model. For example, Java model: 
```java
class Info {
      private Integer id;
      private String name;
      // getter setter
}
```

to typeScript model: 
```shell script
export interface Info {
      id: number;
      name: string;
}
```
      
How to use: <br>
Code -> Generate -> MakeTypeScriptModel
